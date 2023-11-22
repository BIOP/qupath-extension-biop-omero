package qupath.ext.biop.servers.omero.raw.browser;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class AdvancedSearch {
    private final TableView<SearchResult> resultsTableView = new TableView<>();
    private final ObservableList<SearchResult> obsResults = FXCollections.observableArrayList();
    final private static Logger logger = LoggerFactory.getLogger(AdvancedSearch.class);

    private final TextField searchTf;
    private final CheckBox restrictedByName;
    private final CheckBox restrictedByDesc;
    private final CheckBox searchForImages;
    private final CheckBox searchForDatasets;
    private final CheckBox searchForProjects;
    private final CheckBox searchForWells;
    private final CheckBox searchForPlates;
    private final CheckBox searchForScreens;
    private final ComboBox<OmeroRawObjects.Owner> ownedByCombo;
    private final ComboBox<OmeroRawObjects.Group> groupCombo;

    private final int prefScale = 50;

    private final Button searchBtn;
    private final ProgressIndicator progressIndicator2;

    // Search query in separate thread
    private final ExecutorService executorQuery = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("query-processing", true));

    // Load thumbnail in separate thread
    private ExecutorService executorThumbnail;

    private final Pattern patternRow = Pattern.compile("<tr id=\"(.+?)-(.+?)\".+?</tr>", Pattern.DOTALL | Pattern.MULTILINE);
    private final Pattern patternDesc = Pattern.compile("<td class=\"desc\"><a>(.+?)</a></td>");
    private final Pattern patternDate = Pattern.compile("<td class=\"date\">(.+?)</td>");
    private final Pattern patternGroup = Pattern.compile("<td class=\"group\">(.+?)</td>");
    private final Pattern patternLink = Pattern.compile("<td><a href=\"(.+?)\"");

    private final Pattern[] patterns = new Pattern[] {patternDesc, patternDate, patternDate, patternGroup, patternLink};
    private final Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> omeroIcons;
    private Map<Long, BufferedImage> thumbnailBank;

    protected AdvancedSearch(QuPathGUI qupath, OmeroRawClient client, Collection<OmeroRawObjects.Owner> owners, Collection<OmeroRawObjects.Group> groups,
                             Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> omeroIcons, Map<Long, BufferedImage> thumbnailBank,
                             int imgPrefSize) {

        this.omeroIcons = omeroIcons;
        this.thumbnailBank = thumbnailBank;
        BorderPane searchPane = new BorderPane();
        GridPane searchOptionPane = new GridPane();
        GridPane searchResultPane = new GridPane();


        // 'Query' pane
        GridPane queryPane = new GridPane();
        queryPane.setHgap(10.0);
        searchTf = new TextField();
        searchTf.setPromptText("Query");
        queryPane.addRow(0, new Label("Query:"), searchTf);


        // 'Restrict by' pane
        GridPane restrictByPane = new GridPane();
        restrictByPane.setHgap(10.0);
        restrictedByName = new CheckBox("Name");
        restrictedByDesc = new CheckBox("Description");
        restrictByPane.addRow(0, restrictedByName, restrictedByDesc);


        // 'Search for' pane
        GridPane searchForPane = new GridPane();
        searchForPane.setHgap(10.0);
        searchForPane.setVgap(10.0);
        searchForImages = new CheckBox("Images");
        searchForDatasets = new CheckBox("Datasets");
        searchForProjects = new CheckBox("Projects");
        searchForWells = new CheckBox("Wells");
        searchForPlates = new CheckBox("Plates");
        searchForScreens = new CheckBox("Screens");
        searchForPane.addRow(0,  searchForImages, searchForDatasets, searchForProjects);
        searchForPane.addRow(1,  searchForWells, searchForPlates, searchForScreens);
        for (var searchFor: searchForPane.getChildren()) {
            ((CheckBox)searchFor).setSelected(true);
        }

        // 'Owned by' & 'Group' pane
        GridPane comboPane = new GridPane();
        comboPane.setHgap(10.0);
        comboPane.setHgap(10.0);
        ownedByCombo = new ComboBox<>();
        groupCombo = new ComboBox<>();
        ownedByCombo.setMaxWidth(Double.MAX_VALUE);
        groupCombo.setMaxWidth(Double.MAX_VALUE);
        ownedByCombo.getItems().setAll(owners);
        groupCombo.getItems().setAll(groups);
        ownedByCombo.getSelectionModel().selectFirst();
        groupCombo.getSelectionModel().selectFirst();
        ownedByCombo.setConverter(OmeroRawBrowserTools.getOwnerStringConverter(owners));

        // Changing the ComboBox value refreshes the TreeView
        groupCombo.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            Platform.runLater(() -> {
                Map<OmeroRawObjects.Group, List<OmeroRawObjects.Owner>> tempMap = null;
                tempMap = OmeroRawBrowserTools.getGroupUsersMapAvailableForCurrentUser(client);

                var tempOwners = new ArrayList<>(tempMap.get(groupCombo.getSelectionModel().getSelectedItem()));
                if (!tempOwners.containsAll(ownedByCombo.getItems()) || !ownedByCombo.getItems().containsAll(tempOwners)) {

                    ownedByCombo.getItems().setAll(tempOwners);
                    ownedByCombo.getSelectionModel().selectFirst(); // 'All members'
                }
            });
            // if (owners.size() == 1)
            //    owners = new HashSet<>(tempOwners);
        });

        GridPaneUtils.addGridRow(comboPane, 0, 0, "Data owned by", new Label("Owned by:"),  ownedByCombo);
        GridPaneUtils.addGridRow(comboPane, 1, 0, "Data from group", new Label("Group:"), groupCombo);

        // Button pane
        GridPane buttonPane = new GridPane();
        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> {
            searchTf.setText("");
            for (var restrictBy: restrictByPane.getChildren()) {
                ((CheckBox)restrictBy).setSelected(false);
            }
            for (var searchFor: searchForPane.getChildren()) {
                ((CheckBox)searchFor).setSelected(true);
            }
            ownedByCombo.getSelectionModel().selectFirst();
            groupCombo.getSelectionModel().selectFirst();
            resultsTableView.getItems().clear();
        });
        searchBtn = new Button("Search");
        progressIndicator2 = new ProgressIndicator();
        progressIndicator2.setPrefSize(30, 30);
        progressIndicator2.setMinSize(30, 30);
        searchBtn.setOnAction(e -> {
            searchBtn.setGraphic(progressIndicator2);
            // Show progress indicator (loading)
            Platform.runLater(() -> {
                // TODO: next line doesn't work
                searchBtn.setGraphic(progressIndicator2);
                searchBtn.setText(null);
            });

            // Process the query in different thread
            executorQuery.submit(() -> searchQuery(client, imgPrefSize));

            // Reset 'Search' button
            Platform.runLater(() -> {
                searchBtn.setGraphic(null);
                searchBtn.setText("Search");
            });
        });
        resetBtn.setMaxWidth(Double.MAX_VALUE);
        searchBtn.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(resetBtn, Priority.ALWAYS);
        GridPane.setHgrow(searchBtn, Priority.ALWAYS);
        buttonPane.addRow(0,  resetBtn, searchBtn);
        buttonPane.setHgap(5.0);

        Button importBtn = new Button("Import image");
        importBtn.disableProperty().bind(resultsTableView.getSelectionModel().selectedItemProperty().isNull());
        importBtn.setMaxWidth(Double.MAX_VALUE);
        importBtn.setOnAction(e -> {
            String[] URIs =  new String[]{};/*resultsTableView.getSelectionModel().getSelectedItems().stream()
                    .flatMap(item -> {
                        try {
                            return OmeroRawBrowserTools.getURIs(item.link.toURI(), client).stream();
                        } catch (URISyntaxException | IOException ex) {
                            logger.error("Error while opening " + item.name + ": {}", ex.getLocalizedMessage());
                        } catch (DSOutOfServiceException | ExecutionException | DSAccessException ex) {
                            throw new RuntimeException(ex);
                        }
                        return null;
                    })
                    .map(URI::toString)
                    .toArray(String[]::new);*/
            if (URIs.length > 0) {

                List<ProjectImageEntry<BufferedImage>> importedImageEntries = OmeroRawBrowserTools.promptToImportOmeroImages(qupath, URIs);

                //TODO Find a way to get an OmeroRawObjects.OmeroRawObject
                    /*for(ProjectImageEntry<BufferedImage> entry : importedImageEntries) {
                        Optional<OmeroRawObjects.OmeroRawObject> optObj = validObjs.stream()
                                .filter(obj -> {
                                    try {
                                        return obj.getId() == ((OmeroRawImageServer) entry.readImageData().getServer()).getId();
                                    }catch(IOException ex){
                                        return false;
                                    }
                                })
                                .findFirst();
                        optObj.ifPresent(omeroRawObject -> OmeroRawBrowserTools.addContainersAsMetadataFields(entry, omeroRawObject));
                    }*/
            }
            else
                Dialogs.showErrorMessage("No image found", "No image found in OMERO object.");
        });
        resultsTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);


        int row = 0;
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "The query to search", queryPane);
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, null, new Separator());
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "Restrict by", new Label("Restrict by:"));
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "Restrict by", restrictByPane);
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, null, new Separator());
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "Search for", new Label("Search for:"));
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "Search for", searchForPane);
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, null, new Separator());
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, null, comboPane);
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, null, buttonPane);
        GridPaneUtils.addGridRow(searchOptionPane, row++, 0, "Import selected image", importBtn);

        TableColumn<SearchResult, SearchResult> typeCol = new TableColumn<>("Type");
        TableColumn<SearchResult, String> nameCol = new TableColumn<>("Name");
        TableColumn<SearchResult, String> acquisitionCol = new TableColumn<>("Acquired");
        TableColumn<SearchResult, String> importedCol = new TableColumn<>("Imported");
        TableColumn<SearchResult, String> groupCol = new TableColumn<>("Group");
        TableColumn<SearchResult, SearchResult> linkCol = new TableColumn<>("Link");

        typeCol.setCellValueFactory(n -> new ReadOnlyObjectWrapper<>(n.getValue()));
        typeCol.setCellFactory(n -> new TableCell<>() {

            @Override
            protected void updateItem(SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                BufferedImage img = null;
                Canvas canvas = new Canvas(prefScale, prefScale);

                if (item == null || empty) {
                    setTooltip(null);
                    setText(null);
                    return;
                }

                if (item.type.equalsIgnoreCase("project"))
                    img = omeroIcons.get(OmeroRawObjects.OmeroRawObjectType.PROJECT);
                else if (item.type.equalsIgnoreCase("dataset"))
                    img = omeroIcons.get(OmeroRawObjects.OmeroRawObjectType.DATASET);
                else {
                    // To avoid ConcurrentModificationExceptions
                    var it = thumbnailBank.keySet().iterator();
                    synchronized (thumbnailBank) {
                        while (it.hasNext()) {
                            var id = it.next();
                            if (id == item.id) {
                                img = thumbnailBank.get(id);
                                continue;
                            }
                        }
                    }
                }

                if (img != null) {
                    var wi = OmeroRawBrowserTools.paintBufferedImageOnCanvas(img, canvas, prefScale);
                    Tooltip tooltip = new Tooltip();
                    if (item.type.equalsIgnoreCase("image")) {
                        // Setting tooltips on hover
                        ImageView imageView = new ImageView(wi);
                        imageView.setFitHeight(250);
                        imageView.setPreserveRatio(true);
                        tooltip.setGraphic(imageView);
                    } else
                        tooltip.setText(item.name);

                    setText(null);
                    setTooltip(tooltip);
                }

                setGraphic(canvas);
                setAlignment(Pos.CENTER);
            }
        });
        nameCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().name));
        acquisitionCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().acquired.toString()));
        importedCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().imported.toString()));
        groupCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().group));
        linkCol.setCellValueFactory(n -> new ReadOnlyObjectWrapper<>(n.getValue()));
        linkCol.setCellFactory(n -> new TableCell<>() {
            private final Button button = new Button("Link");

            @Override
            protected void updateItem(SearchResult item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null) {
                    setGraphic(null);
                    return;
                }

                button.setOnAction(e -> QuPathGUI.openInBrowser(item.link.toString()));
                setGraphic(button);
            }
        });

        resultsTableView.getColumns().add(typeCol);
        resultsTableView.getColumns().add(nameCol);
        resultsTableView.getColumns().add(acquisitionCol);
        resultsTableView.getColumns().add(importedCol);
        resultsTableView.getColumns().add(groupCol);
        resultsTableView.getColumns().add(linkCol);
        resultsTableView.setItems(obsResults);
        resultsTableView.getColumns().forEach(e -> e.setStyle( "-fx-alignment: CENTER;"));

        resultsTableView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                SearchResult selectedItem = resultsTableView.getSelectionModel().getSelectedItem();
                if (selectedItem != null) {
                    List<URI> URIs = new ArrayList<>();//OmeroRawBrowserTools.getURIs(selectedItem.link.toURI(), client);
                    var uriStrings = URIs.parallelStream().map(URI::toString).toArray(String[]::new);
                    if (URIs.size() > 0) {
                        List<ProjectImageEntry<BufferedImage>> importedImageEntries =
                                OmeroRawBrowserTools.promptToImportOmeroImages(qupath, uriStrings);

                        //TODO Find a way to get an OmeroRawObjects.OmeroRawObject
                           /* for(ProjectImageEntry<BufferedImage> entry : afterImport)
                                OmeroRawBrowserTools.addContainersAsMetadataFields(client, entry);*/
                    }
                    else
                        Dialogs.showErrorMessage("No image found", "No image found in OMERO object.");
                }
            }
        });

        resultsTableView.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            if (n == null)
                return;

            if (resultsTableView.getSelectionModel().getSelectedItems().size() == 1)
                importBtn.setText("Import " + n.type);
            else
                importBtn.setText("Import OMERO objects");
        });


        searchResultPane.addRow(0,  resultsTableView);
        searchOptionPane.setVgap(10.0);

        searchPane.setLeft(searchOptionPane);
        searchPane.setRight(searchResultPane);

        Insets insets = new Insets(10);
        BorderPane.setMargin(searchOptionPane, insets);
        BorderPane.setMargin(searchResultPane, insets);

        var dialog = Dialogs.builder().content(searchPane).build();
        dialog.setOnCloseRequest(e -> {
            // Make sure we're not still sending requests
            executorQuery.shutdownNow();
            executorThumbnail.shutdownNow();
        });
        dialog.showAndWait();
    }


    // TODO find where it is called and update it. Advanced Search do not work for the moment
    private void searchQuery(OmeroRawClient client, int imgPrefSize) {
        List<SearchResult> results = new ArrayList<>();

        List<String> fields = new ArrayList<>();
        if (restrictedByName.isSelected()) fields.add("field=name");
        if (restrictedByDesc.isSelected()) fields.add("field=description");

        List<OmeroRawObjects.OmeroRawObjectType> datatypes = new ArrayList<>();
        if (searchForImages.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.IMAGE);
        if (searchForDatasets.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.DATASET);
        if (searchForProjects.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.PROJECT);
        if (searchForWells.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.WELL);
        if (searchForPlates.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.PLATE);
        if (searchForScreens.isSelected()) datatypes.add(OmeroRawObjects.OmeroRawObjectType.SCREEN);

        OmeroRawObjects.Owner owner = ownedByCombo.getSelectionModel().getSelectedItem();
        OmeroRawObjects.Group group = groupCombo.getSelectionModel().getSelectedItem();

        String response = null;/*OmeroRequests.requestAdvancedSearch(
                    serverURI.getScheme(),
                    serverURI.getHost(),
                    serverURI.getPort(),
                    searchTf.getText(),
                    fields.toArray(new String[0]),
                    datatypes.stream().map(e -> "datatype=" + e.toURLString()).toArray(String[]::new),
                    group,
                    owner
            );*/

        if (!response.contains("No results found"))
            results = parseHTML(response, client.getServerURI());

        populateThumbnailBank(results, client, imgPrefSize);
        updateTableView(results);

    }

    private List<SearchResult> parseHTML(String response, URI serverURI) {
        List<SearchResult> searchResults = new ArrayList<>();
        Matcher rowMatcher = patternRow.matcher(response);
        while (rowMatcher.find()) {
            String[] values = new String[7];
            String row = rowMatcher.group(0);
            values[0] = rowMatcher.group(1);
            values[1] = rowMatcher.group(2);
            String value = "";

            int nValue = 2;
            for (var pattern: patterns) {
                Matcher matcher = pattern.matcher(row);
                if (matcher.find()) {
                    value = matcher.group(1);
                    row = row.substring(matcher.end());
                }
                values[nValue++] = value;
            }

            try {
                SearchResult obj = new SearchResult(values, serverURI);
                searchResults.add(obj);
            } catch (Exception e) {
                logger.error("Could not parse search result. {}", e.getLocalizedMessage());
            }
        }

        return searchResults;
    }

    private void updateTableView(List<SearchResult> results) {
        resultsTableView.getItems().setAll(results);
        Platform.runLater(() -> resultsTableView.refresh());
    }

    /**
     * Send a request to batch load thumbnails that are not already
     * stored in {@code thumbnailBank}.
     * @param results
     */
    private void populateThumbnailBank(List<SearchResult> results, OmeroRawClient client, int imgPrefSize) {

        List<SearchResult> thumbnailsToQuery = results.parallelStream()
                .filter(e -> {
                    // To avoid ConcurrentModificationExceptions
                    synchronized (thumbnailBank) {
                        for (var id: thumbnailBank.keySet()) {
                            if (id == e.id)
                                return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());

        if (thumbnailsToQuery.isEmpty())
            return;

        if (executorThumbnail != null)
            executorThumbnail.shutdownNow();
        executorThumbnail = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("batch-thumbnail-request", true));

        for (var searchResult: thumbnailsToQuery) {
            executorThumbnail.submit(() -> {
                BufferedImage thumbnail = OmeroRawTools.getThumbnail(client, searchResult.id, imgPrefSize);
                if (thumbnail != null) {
                    thumbnailBank.put((long)searchResult.id, thumbnail);	// 'Put' shouldn't need synchronized key
                    Platform.runLater(() -> resultsTableView.refresh());
                }
            });
        }
    }

    //TODO if this search need to be implemented, we need first to use the OmeroRawObjects class
    private class SearchResult {
        private final String type;
        private final int id;
        private final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        private final String name;
        private final Date acquired;
        private final Date imported;
        private final String group;
        private final URL link;

        private SearchResult(String[] values, URI serverURI) throws ParseException, MalformedURLException {
            this.type = values[0];
            this.id = Integer.parseInt(values[1]);
            this.name = values[2];
            this.acquired = dateFormat.parse(values[3]);
            this.imported = dateFormat.parse(values[4]);
            this.group = values[5];
            this.link = URI.create(serverURI.getScheme() + "://" + serverURI.getHost() + values[6]).toURL();
        }
    }

}
