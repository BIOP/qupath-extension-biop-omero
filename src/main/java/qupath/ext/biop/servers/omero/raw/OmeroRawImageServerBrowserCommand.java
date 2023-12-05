/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2018 - 2021 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */

package qupath.ext.biop.servers.omero.raw;

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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javafx.event.EventHandler;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.ProjectCommands;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.tools.GuiTools;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.CommentAnnotation;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.FileAnnotation;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.LongAnnotation;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.MapAnnotation;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.OmeroRawAnnotationType;
import qupath.ext.biop.servers.omero.raw.OmeroRawAnnotations.TagAnnotation;
import qupath.lib.projects.ProjectImageEntry;

import javax.imageio.ImageIO;

/**
 * Command to browse a specified OMERO server.
 *
 * @author Melvin Gelbard
 */
// TODO: Orphaned folder is still 'selectable' via arrow keys (despite being disabled), which looks like a JavaFX bug..
// TODO: If switching users while the browser is opened, nothing will load (but everything stays clickable).
public class OmeroRawImageServerBrowserCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(OmeroRawImageServerBrowserCommand.class);
    private static final String BOLD = "-fx-font-weight: bold";

    private final QuPathGUI qupath;
    private Stage dialog;

    // OmeroRawClient with server to browse
    private final OmeroRawClient client;
    private final URI serverURI;

    // GUI left
    private ComboBox<OmeroRawObjects.Owner> comboOwner;
    private ComboBox<OmeroRawObjects.Group> comboGroup;
    private Set<OmeroRawObjects.Owner> owners;
    private Set<OmeroRawObjects.Group> groups;
    private OmeroRawObjects.Group defaultGroup;
    private OmeroRawObjects.Owner defaultOwner;
    private TreeView<OmeroRawObjects.OmeroRawObject> tree;
    private OmeroRawObjects.OrphanedFolder orphanedFolder;
    private TextField filter;

    // GUI right
    private TableView<Integer> description;
    private Canvas canvas;
    private final int imgPrefSize = 256;

    // GUI top and down
    private Label loadingChildrenLabel;
    private Label loadingThumbnailLabel;
    private Label loadingOrphanedLabel;
    private Button importBtn;

    // Other
    private StringConverter<OmeroRawObjects.Owner> ownerStringConverter;
    private Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> omeroIcons;
    private ExecutorService executorTable;		// Get TreeView item children in separate thread
    private ExecutorService executorThumbnails;	// Get image thumbnails in separate thread

    // Browser data 'storage'
   // private List<OmeroRawObject> serverChildrenList;
    private Map<OmeroRawObjects.Group, Map<OmeroRawObjects.Owner, List<OmeroRawObjects.OmeroRawObject>>> groupOwnersChildrenMap;
    private ObservableList<OmeroRawObjects.OmeroRawObject> orphanedImageList;
    private Map<OmeroRawObjects.Group, List<OmeroRawObjects.Owner>> groupMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> projectMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> screenMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> plateMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> wellMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> datasetMap;
    private Map<Long, BufferedImage> thumbnailBank;
    private IntegerProperty currentOrphanedCount;

    private final String[] orphanedAttributes = new String[] {"Name"};

    private final String[] projectAttributes = new String[] {"Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. datasets"};

    private final String[] datasetAttributes = new String[] {"Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. images"};

    private final String[] screenAttributes = new String[] {"Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. plates"};

    private final String[] plateAttributes = new String[] {"Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. wells"};

    private final String[] wellAttributes = new String[] {"Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. images"};

    private final String[] imageAttributes = new String[] {"Name",
            "Id",
            "Owner",
            "Group",
            "Acquisition date",
            "Image width",
            "Image height",
            "Num. channels",
            "Num. z-slices",
            "Num. timepoints",
            "Pixel size X",
            "Pixel size Y",
            "Pixel size Z",
            "Pixel type"};

    OmeroRawImageServerBrowserCommand(QuPathGUI qupath, OmeroRawClient client) {
        this.qupath = qupath;
        this.client = Objects.requireNonNull(client);
        this.serverURI = client.getServerURI();
    }

    Stage getStage() {
        return dialog;
    }

    @Override
    public void run() {
        boolean loggedIn = true;
        if (!client.isLoggedIn())
            loggedIn = client.logIn();

        if (!loggedIn)
            return;

        // Initialize class variables
        //serverChildrenList = new ArrayList<>();
        groupOwnersChildrenMap = new ConcurrentHashMap<>();
        orphanedImageList = FXCollections.observableArrayList();
        orphanedFolder = new OmeroRawObjects.OrphanedFolder(orphanedImageList);
        currentOrphanedCount = orphanedFolder.getCurrentCountProperty();
        thumbnailBank = new ConcurrentHashMap<>();
        projectMap = new ConcurrentHashMap<>();
        screenMap = new ConcurrentHashMap<>();
        plateMap = new ConcurrentHashMap<>();
        wellMap = new ConcurrentHashMap<>();
        datasetMap = new ConcurrentHashMap<>();
        executorTable = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("children-loader", true));
        executorThumbnails = Executors.newFixedThreadPool(PathPrefs.numCommandThreadsProperty().get(), ThreadTools.createThreadFactory("thumbnail-loader", true));

        tree = new TreeView<>();
        owners = new HashSet<>();
        groups = new HashSet<>();
        comboGroup = new ComboBox<>();
        comboOwner = new ComboBox<>();
        filter = new TextField();

        BorderPane mainPane = new BorderPane();
        BorderPane serverInfoPane = new BorderPane();
        GridPane serverAttributePane = new GridPane();
        SplitPane browsePane = new SplitPane();
        GridPane browseLeftPane = new GridPane();
        GridPane browseRightPane = new GridPane();
        GridPane loadingInfoPane = new GridPane();

        var progressChildren = new ProgressIndicator();
        progressChildren.setPrefSize(15, 15);
        loadingChildrenLabel = new Label("Loading OMERO objects", progressChildren);


        var progressThumbnail = new ProgressIndicator();
        progressThumbnail.setPrefSize(15, 15);
        loadingThumbnailLabel = new Label("Loading thumbnail", progressThumbnail);
        loadingThumbnailLabel.setOpacity(0.0);

        var progressOrphaned = new ProgressIndicator();
        progressOrphaned.setPrefSize(15.0, 15.0);
        loadingOrphanedLabel = new Label();
        loadingOrphanedLabel.setGraphic(progressOrphaned);

        GridPaneUtils.addGridRow(loadingInfoPane, 0, 0, "OMERO objects are loaded in the background", loadingChildrenLabel);
        GridPaneUtils.addGridRow(loadingInfoPane, 1, 0, "OMERO objects are loaded in the background", loadingOrphanedLabel);
        GridPaneUtils.addGridRow(loadingInfoPane, 2, 0, "Thumbnails are loaded in the background", loadingThumbnailLabel);

        // Info about the server to display at the top
        var hostLabel = new Label(serverURI.getHost());
        var usernameLabel = new Label();

        usernameLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            if (client.getUsername().isEmpty() && client.isLoggedIn())
                return "public";
            return client.getUsername();
        }, client.usernameProperty(), client.logProperty()));

        // 'Num of open images' text and number are bound to the size of client observable list
        var nOpenImagesText = new Label();
        var nOpenImages = new Label();
        nOpenImagesText.textProperty().bind(Bindings.createStringBinding(() -> "Open image" + (client.getURIs().size() > 1 ? "s" : "") + ": ", client.getURIs()));
        nOpenImages.textProperty().bind(Bindings.concat(Bindings.size(client.getURIs()), ""));
        hostLabel.setStyle(BOLD);
        usernameLabel.setStyle(BOLD);
        nOpenImages.setStyle(BOLD);

        Label isReachable = new Label();
        isReachable.graphicProperty().bind(Bindings.createObjectBinding(() -> OmeroRawTools.createStateNode(client.isLoggedIn()), client.logProperty()));

        serverAttributePane.addRow(0, new Label("Server: "), hostLabel, isReachable);
        serverAttributePane.addRow(1, new Label("Username: "), usernameLabel);
        serverAttributePane.addRow(2, nOpenImagesText, nOpenImages);
        serverInfoPane.setLeft(serverAttributePane);
        serverInfoPane.setRight(loadingInfoPane);

        // Get OMERO icons (project and dataset icons)
        omeroIcons = getOmeroIcons();

        // Create converter from Owner object to proper String
        ownerStringConverter = new StringConverter<>() {
            @Override
            public String toString(OmeroRawObjects.Owner owner) {
                if (owner != null)
                    return owner.getName();
                return null;
            }

            @Override
            public OmeroRawObjects.Owner fromString(String string) {
                return comboOwner.getItems().stream().filter(ap ->
                        ap.getName().equals(string)).findFirst().orElse(null);
            }
        };

        currentOrphanedCount.bind(Bindings.createIntegerBinding(() -> Math.toIntExact(filterList(orphanedImageList,
                        comboGroup.getSelectionModel().getSelectedItem(),
                        comboOwner.getSelectionModel().getSelectedItem(),
                        null).size()),
                // Binding triggered when the following change: loadingProperty/selected Group/selected Owner
                orphanedFolder.getLoadingProperty(), comboGroup.getSelectionModel().selectedItemProperty(), comboOwner.getSelectionModel().selectedItemProperty())
        );


        // Bind the top label to the amount of orphaned images
        loadingOrphanedLabel.textProperty().bind(Bindings.when(orphanedFolder.getLoadingProperty()).then(Bindings.concat("Loading image list (")
                .concat(Bindings.size(orphanedFolder.getImageList()))
                .concat("/"+ orphanedFolder.getTotalChildCount() + ")")).otherwise(Bindings.concat("")));
        loadingOrphanedLabel.opacityProperty().bind(Bindings.createDoubleBinding(() -> orphanedFolder.getLoadingProperty().get() ? 1.0 : 0, orphanedFolder.getLoadingProperty()));

        orphanedFolder.setLoading(false);

        // Initialises the comboboxes with all the available groups and set select the default group and owner
        groupMap = OmeroRawBrowserTools.getGroupUsersMapAvailableForCurrentUser(client);
        defaultGroup = OmeroRawBrowserTools.getDefaultGroupItem(client);
        defaultOwner = OmeroRawBrowserTools.getDefaultOwnerItem(client);
        groups=   groupMap.keySet();

        comboGroup.getItems().setAll(groups);
        comboGroup.getSelectionModel().select(defaultGroup);

        owners.add(OmeroRawObjects.Owner.getAllMembersOwner());
        comboOwner.getItems().setAll(groupMap.get(comboGroup.getSelectionModel().getSelectedItem()));
        comboOwner.getSelectionModel().select(defaultOwner);
        comboOwner.setConverter(ownerStringConverter);

        // Changing the ComboBox value refreshes the TreeView
        comboOwner.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> refreshTree());
        comboGroup.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> refreshTree());

        OmeroRawObjectTreeItem root = new OmeroRawObjectTreeItem(new OmeroRawObjects.Server(serverURI));


        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(n -> new OmeroObjectCell());
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                var selectedItem = tree.getSelectionModel().getSelectedItem().getValue();
                if (selectedItem != null && selectedItem.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE && isSupported(selectedItem)) {
                    if (qupath.getProject() == null) {
                        try {
                            qupath.openImage(QuPathGUI.getInstance().getViewer(), createObjectURI(selectedItem),  true, true);
                        } catch (IOException ex) {
                            Dialogs.showErrorMessage("Open image", ex);
                        }
                    }
                    else {
                        HashSet<ProjectImageEntry<BufferedImage>> beforeImport = new HashSet<>(qupath.getProject().getImageList());
                        promptToImportOmeroImages(createObjectURI(selectedItem));
                        HashSet<ProjectImageEntry<BufferedImage>> afterImport = new HashSet<>(qupath.getProject().getImageList());

                        // filter newly imported images
                        afterImport.removeAll(beforeImport);

                        for(ProjectImageEntry<BufferedImage> entry : afterImport)
                           OmeroRawBrowserTools.addContainersAsMetadataFields(entry, selectedItem);
                    }
                }
            }
        });

        MenuItem moreInfoItem = new MenuItem("More info...");
        MenuItem openBrowserItem = new MenuItem("Open in browser");
        MenuItem clipboardItem = new MenuItem("Copy to clipboard");
        MenuItem collapseItem = new MenuItem("Collapse all items");

        // 'More info..' will open new AdvancedObjectInfo pane
        moreInfoItem.setOnAction(ev -> new AdvancedObjectInfo(tree.getSelectionModel().getSelectedItem().getValue()));
        moreInfoItem.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull()
                .or(Bindings.size(tree.getSelectionModel().getSelectedItems()).isNotEqualTo(1)
                        .or(Bindings.createBooleanBinding(() -> tree.getSelectionModel().getSelectedItem() != null && tree.getSelectionModel().getSelectedItem().getValue().getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER,
                                tree.getSelectionModel().selectedItemProperty()))));

        // Opens the OMERO object in a browser
        openBrowserItem.setOnAction(ev -> {
            var selected = tree.getSelectionModel().getSelectedItems();
            if (selected != null && !selected.isEmpty() && selected.size() == 1) {
                if (selected.get(0).getValue() instanceof OmeroRawObjects.OrphanedFolder) {
                    Dialogs.showPlainMessage("Requesting orphaned folder", "Link to orphaned folder does not exist!");
                    return;
                }
                QuPathGUI.openInBrowser(createObjectURI(selected.get(0).getValue()).replace("-server",""));
            }
        });
        openBrowserItem.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull()
                .or(Bindings.size(tree.getSelectionModel().getSelectedItems()).isNotEqualTo(1)));

        // Clipboard action will *not* fetch all the images in the selected object(s)
        clipboardItem.setOnAction(ev -> {
            var selected = tree.getSelectionModel().getSelectedItems();
            if (selected != null && !selected.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                List<String> uris = new ArrayList<>();
                for (var obj: selected) {
                    // If orphaned get all children items and add them to list, else create URI for object
                    if (obj.getValue().getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)
                        uris.addAll(getObjectsURI(obj.getValue()));
                    else
                        uris.add(createObjectURI(obj.getValue()));
                }

                if (uris.size() == 1)
                    content.putString(uris.get(0));
                else
                    content.putString("[" + String.join(", ", uris) + "]");
                Clipboard.getSystemClipboard().setContent(content);
                Utils.infoLog(logger, "Copy URI to clipboard", "URI" + (uris.size() > 1 ? "s " : " ") + "successfully copied to clipboard", true);
            } else
                Utils.warnLog(logger,"Copy URI to clipboard", "The item needs to be selected first!", true);
        });

        // Collapse all items in the tree
        collapseItem.setOnAction(ev -> collapseTreeView(tree.getRoot()));

        // Add the items to the context menu
        tree.setContextMenu(new ContextMenu(moreInfoItem, openBrowserItem, clipboardItem, collapseItem));



        // If the currently opened image belongs to the server that we are browsing, switch combo to the relevant group
        /*var imageData = qupath.getImageData();

       if (imageData != null && (imageData.getServer() instanceof OmeroRawImageServer)) {
            var server = (OmeroRawImageServer)imageData.getServer();

            try {
                var tempImageURI = server.getURIs().iterator().next();
                if (OmeroRawTools.getServerURI(tempImageURI).equals(serverURI) && OmeroRawClient.canBeAccessed(tempImageURI, OmeroRawObjects.OmeroRawObjectType.IMAGE)) {
                    try {
                        long groupId = client.getGateway().getFacility(BrowseFacility.class).getImage(client.getContext(), server.getId()).getGroupId();
                        String groupName = client.getGateway().getAdminService(client.getContext()).getGroup(groupId).getName().getValue();
                        OmeroRawObjects.Group group = new OmeroRawObjects.Group(groupId, groupName);

                        //groups.add(group);

                        //comboGroup.getItems().setAll(groups);
                        comboGroup.getSelectionModel().select(group);
                    } catch (Exception ex) {
                        logger.error("Could not parse OMERO group: {}", ex.getLocalizedMessage());
                        groups.add(OmeroRawObjects.Group.getAllGroupsGroup());
                        comboGroup.getItems().setAll(groups);
                    }
                } else {
                    comboGroup.getItems().setAll(groups);
                }
            } catch (ConnectException ex) {
                logger.warn(ex.getLocalizedMessage());
                logger.info("Will not fetch the current OMERO group.");
            }
        }*/

        // If nothing is selected (i.e. currently opened image is not from the same server/an error occurred), select first item
        if (comboGroup.getSelectionModel().isEmpty())
            comboGroup.getSelectionModel().selectFirst();

        description = new TableView<>();
        TableColumn<Integer, String> attributeCol = new TableColumn<>("Attribute");
        TableColumn<Integer, String> valueCol = new TableColumn<>("Value");

        // Set the width of the columns to half the table's width each
        attributeCol.prefWidthProperty().bind(description.widthProperty().divide(4));
        valueCol.prefWidthProperty().bind(description.widthProperty().multiply(0.75));

        attributeCol.setCellValueFactory(cellData -> {
            var selectedItems = tree.getSelectionModel().getSelectedItems();
            if (cellData != null && selectedItems.size() == 1 && selectedItems.get(0).getValue() != null) {
                var type = selectedItems.get(0).getValue().getType();
                if (type == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)
                    return new ReadOnlyObjectWrapper<>(orphanedAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.PROJECT)
                    return new ReadOnlyObjectWrapper<>(projectAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.DATASET)
                    return new ReadOnlyObjectWrapper<>(datasetAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.SCREEN)
                    return new ReadOnlyObjectWrapper<>(screenAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.PLATE)
                    return new ReadOnlyObjectWrapper<>(plateAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.WELL)
                    return new ReadOnlyObjectWrapper<>(wellAttributes[cellData.getValue()]);
                else if (type == OmeroRawObjects.OmeroRawObjectType.IMAGE)
                    return new ReadOnlyObjectWrapper<>(imageAttributes[cellData.getValue()]);
            }
            return new ReadOnlyObjectWrapper<>("");

        });
        valueCol.setCellValueFactory(cellData -> {
            var selectedItems = tree.getSelectionModel().getSelectedItems();
            if (cellData != null && selectedItems.size() == 1 && selectedItems.get(0).getValue() != null)
                return getObjectInfo(cellData.getValue(), selectedItems.get(0).getValue());
            return new ReadOnlyObjectWrapper<>();
        });
        valueCol.setCellFactory(n -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    setTooltip(new Tooltip(item));
                }
            }
        });


        tree.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            clearCanvas();
            if (n != null) {
                if (description.getPlaceholder() == null)
                    description.setPlaceholder(new Label("Multiple elements selected"));
                var selectedItems = tree.getSelectionModel().getSelectedItems();

                updateDescription();
                if (selectedItems.size() == 1) {
                    var selectedObjectLocal = n.getValue();
                    if (selectedItems.get(0) != null && selectedItems.get(0).getValue().getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                        // Check if thumbnail was previously cached
                        if (thumbnailBank.containsKey(selectedObjectLocal.getId()))
                            paintBufferedImageOnCanvas(thumbnailBank.get(selectedObjectLocal.getId()), canvas, imgPrefSize);
                        else {
                            // Get thumbnail from JSON API in separate thread (and show progress indicator)
                            loadingThumbnailLabel.setOpacity(1.0);
                            executorThumbnails.submit(() -> {
                                // Note: it is possible that another task for the same id exists, but it
                                // shouldn't cause inconsistent results anyway, since '1 id = 1 thumbnail'
                                BufferedImage img = OmeroRawTools.getThumbnail(client, selectedObjectLocal.getId(), imgPrefSize);

                                if (img != null) {
                                    thumbnailBank.put(selectedObjectLocal.getId(), img);
                                    paintBufferedImageOnCanvas(thumbnailBank.get(selectedObjectLocal.getId()), canvas, imgPrefSize);
                                }
                                Platform.runLater(() -> loadingThumbnailLabel.setOpacity(0));
                            });
                        }
                    } else {
                        // To avoid empty space at the top
                        canvas.setWidth(0);
                        canvas.setHeight(0);
                    }
                } else {
                    // If multiple elements are selected, collapse canvas
                    canvas.setWidth(0);
                    canvas.setHeight(0);
                }
            }
        });

        filter.setPromptText("Filter project names");
        filter.textProperty().addListener((v, o, n) -> {
            refreshTree();
            if (n.isEmpty())
                collapseTreeView(tree.getRoot());
            else
                expandTreeView(tree.getRoot());
        });

        Button advancedSearchBtn = new Button("Advanced...");
        advancedSearchBtn.setOnAction(e -> new AdvancedSearch());
        GridPane searchAndAdvancedPane = new GridPane();
        GridPaneUtils.addGridRow(searchAndAdvancedPane, 0, 0, null, filter, advancedSearchBtn);

        importBtn = new Button("Import image");

        // Text on button will change according to OMERO object selected
        importBtn.textProperty().bind(Bindings.createStringBinding(() -> {
            var selected = tree.getSelectionModel().getSelectedItems();
            if (selected.isEmpty())
                return "Import OMERO image to QuPath";
            else if (selected.size() > 1)
                return "Import selected to QuPath";
            else
                return "Import OMERO " + selected.get(0).getValue().getType().toString().toLowerCase() + " to QuPath";
        }, tree.getSelectionModel().selectedItemProperty()));

        // Disable import button if no item is selected or selected item is not compatible
        importBtn.disableProperty().bind(
                Bindings.size(tree.getSelectionModel().getSelectedItems()).lessThan(1).or(
                        Bindings.createBooleanBinding(() -> !tree.getSelectionModel().getSelectedItems().stream().allMatch(obj -> isSupported(obj.getValue())),
                                tree.getSelectionModel().selectedItemProperty())
                )
        );

        // Import button will fetch all the images in the selected object(s) and check their validity
        importBtn.setOnMouseClicked(e -> {
            var selected = tree.getSelectionModel().getSelectedItems();
            var validObjs = selected.parallelStream()
                    .flatMap(item -> listAllImagesToImport(item.getValue(),
                            comboGroup.getSelectionModel().getSelectedItem(),
                            comboOwner.getSelectionModel().getSelectedItem()).parallelStream())
                    .filter(OmeroRawImageServerBrowserCommand::isSupported)
                    .collect(Collectors.toList());

            var validUris = validObjs.stream()
                    .map(this::createObjectURI)
                    .toArray(String[]::new);

            if (validUris.length == 0) {
                Dialogs.showErrorMessage("No images", "No valid images found in selected item" + (selected.size() > 1 ? "s" : "") + "!");
                return;
            }
            if (qupath.getProject() == null) {
                if (validUris.length == 1) {
                    try {
                        qupath.openImage(QuPathGUI.getInstance().getViewer(), validUris[0], true, true);
                    } catch (IOException ex) {
                        Dialogs.showErrorMessage("Open image", ex);
                    }
                }
                else
                    Dialogs.showErrorMessage("Open OMERO images", "If you want to handle multiple images, you need to create a project first."); // Same as D&D for images
                return;
            }
            //TODO wait new qupath version to remove before and after import images because prompt to import images should return the list of newly images
            HashSet<ProjectImageEntry<BufferedImage>> beforeImport = new HashSet<>(qupath.getProject().getImageList());
            promptToImportOmeroImages(validUris);
            HashSet<ProjectImageEntry<BufferedImage>> afterImport = new HashSet<>(qupath.getProject().getImageList());

            // filter newly imported images
            afterImport.removeAll(beforeImport);
            for(ProjectImageEntry<BufferedImage> entry : afterImport) {

                String[] query = entry.getServerBuilder().getURIs().iterator().next().getQuery().split("-");
                long id = Long.parseLong(query[query.length-1]);

                Optional<OmeroRawObjects.OmeroRawObject> optObj = validObjs.stream()
                            .filter(obj -> obj.getId() == id)
                            .findFirst();
                optObj.ifPresent(omeroRawObject -> OmeroRawBrowserTools.addContainersAsMetadataFields(entry, omeroRawObject));
            }
        });

        GridPaneUtils.addGridRow(browseLeftPane, 0, 0, "Filter by", comboGroup, comboOwner);
        GridPaneUtils.addGridRow(browseLeftPane, 1, 0, null, tree, tree);
        GridPaneUtils.addGridRow(browseLeftPane, 2, 0, null, searchAndAdvancedPane, searchAndAdvancedPane);
        GridPaneUtils.addGridRow(browseLeftPane, 3, 0, null, importBtn, importBtn);

        canvas = new Canvas();
        canvas.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 4, 0, 1, 1);");
        description.getColumns().add(attributeCol);
        description.getColumns().add(valueCol);

        GridPaneUtils.addGridRow(browseRightPane, 0, 0, null, canvas);
        GridPaneUtils.addGridRow(browseRightPane, 1, 0, null, description);

        // Set alignment of canvas (with thumbnail)
        GridPane.setHalignment(canvas, HPos.CENTER);

        // Set HGrow and VGrow
        GridPane.setHgrow(comboOwner, Priority.ALWAYS);
        GridPane.setHgrow(comboGroup, Priority.ALWAYS);
        GridPane.setHgrow(description, Priority.ALWAYS);
        GridPane.setHgrow(tree, Priority.ALWAYS);
        GridPane.setHgrow(filter, Priority.ALWAYS);
        GridPane.setHgrow(importBtn, Priority.ALWAYS);
        GridPane.setVgrow(description, Priority.ALWAYS);
        GridPane.setVgrow(tree, Priority.ALWAYS);

        // Set max width & height
        comboOwner.setMaxWidth(Double.MAX_VALUE);
        comboGroup.setMaxWidth(Double.MAX_VALUE);
        filter.setMaxWidth(Double.MAX_VALUE);
        browseLeftPane.setMaxWidth(Double.MAX_VALUE);
        importBtn.setMaxWidth(Double.MAX_VALUE);
        description.setMaxHeight(Double.MAX_VALUE);

        // Set paddings & gaps
        serverInfoPane.setPadding(new Insets(5, 15, 5, 5));
        serverAttributePane.setHgap(10.0);

        // Set specific sizes
        browsePane.setPrefWidth(700.0);
        browsePane.setDividerPosition(0, 0.5);

        mainPane.setTop(serverInfoPane);
        mainPane.setCenter(browsePane);
        browsePane.getItems().addAll(browseLeftPane, browseRightPane);


        dialog = new Stage();
        client.logProperty().addListener((v, o, n) -> {
            if (!n)
                requestClose();
        });
        dialog.sizeToScene();
        QuPathGUI qupath = QuPathGUI.getInstance();
        if (qupath != null)
            dialog.initOwner(QuPathGUI.getInstance().getStage());
        dialog.setTitle("OMERO raw server");
        dialog.setScene(new Scene(mainPane));
        dialog.setOnCloseRequest(e -> {
            shutdownPools();
            dialog = null;
            OmeroRawExtension.getOpenedRawBrowsers().remove(client);
        });
        dialog.showAndWait();
    }


    /**
     * If something else than an image is selected in the browser, then it lists all images contained the selected container.
     *
     * @param uri
     * @param group
     * @param owner
     * @return List of available images in the selected container
     */
    private List<OmeroRawObjects.OmeroRawObject> listAllImagesToImport(OmeroRawObjects.OmeroRawObject uri, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner){
        switch (uri.getType()){
            case PROJECT:
            case SCREEN:
            case PLATE:
                var temp = getChildren(uri, group, owner);
                List<OmeroRawObjects.OmeroRawObject> out = new ArrayList<>();
                for (var subTemp: temp) {
                    out.addAll(listAllImagesToImport(subTemp, group, owner));
                }
                return out;
            case DATASET:
            case WELL:
            case ORPHANED_FOLDER:
                return getChildren(uri, group, owner);
            default:
                return Collections.singletonList(uri);
        }
    }

    /**
     * Return a list of all children of the specified parentObj, either by requesting them
     * to the server or by retrieving the stored value from the maps. If a request was
     * necessary, the value will be stored in the map to avoid future unnecessary computation.
     * <p>
     * No filter is applied to the object's children.
     *
     * @param parentObj
     * @return list of parentObj's children
     */
    private List<OmeroRawObjects.OmeroRawObject> getChildren(OmeroRawObjects.OmeroRawObject parentObj, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner) {
        // Check if we already have the children for this OmeroObject (avoid sending request)
        if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER && groupOwnersChildrenMap.containsKey(group) && groupOwnersChildrenMap.get(group).containsKey(owner))
            return groupOwnersChildrenMap.get(group).get(owner);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER && orphanedImageList.size() > 0)
            return orphanedImageList;
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT && projectMap.containsKey(parentObj))
            return projectMap.get(parentObj);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SCREEN && screenMap.containsKey(parentObj))
            return screenMap.get(parentObj);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE && plateMap.containsKey(parentObj))
            return plateMap.get(parentObj);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.WELL && wellMap.containsKey(parentObj))
            return wellMap.get(parentObj);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET && datasetMap.containsKey(parentObj))
            return datasetMap.get(parentObj);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE)
            return new ArrayList<>();

        List<OmeroRawObjects.OmeroRawObject> children;

        // If orphaned folder, return all orphaned images
        if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)
            return orphanedImageList;

        // switch the client to the current group
        if(this.client.getContext().getGroupID() != group.getId())
            this.client.switchGroup(group.getId());

        // Read children and populate maps
        children = OmeroRawBrowserTools.readOmeroObjectsItems(parentObj, this.client, group, owner);

        // If parentObj is a Server, add all the orphaned datasets (orphaned images are in 'Orphaned images' folder)
        if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER) {
            // read orphaned images
            orphanedImageList.addAll(OmeroRawBrowserTools.readOrphanedImagesItem(client, group, owner));

            // update the list of already loaded items
            if(groupOwnersChildrenMap.containsKey(group))
                groupOwnersChildrenMap.get(group).put(owner, children);
            else {
                Map<OmeroRawObjects.Owner, List<OmeroRawObjects.OmeroRawObject>> ownerMap = new HashMap<>();
                ownerMap.put(owner, children);
                groupOwnersChildrenMap.put(group, ownerMap);
            }
        } else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT) {
            projectMap.put(parentObj, children);
        } else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET) {
            datasetMap.put(parentObj, children);
        }else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SCREEN) {
           screenMap.put(parentObj, children);
        }else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE) {
            plateMap.put(parentObj, children);
        }else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.WELL) {
            wellMap.put(parentObj, children);
        }

        return children;
    }

    private Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> getOmeroIcons() {
        Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> map = new HashMap<>();

        try {
            // Load project icon
            map.put(OmeroRawObjects.OmeroRawObjectType.PROJECT, ImageIO.read(getClass().getClassLoader().getResource("images/folder16.png")));

            // Load dataset icon
            map.put(OmeroRawObjects.OmeroRawObjectType.DATASET, ImageIO.read(getClass().getClassLoader().getResource("images/folder_image16.png")));

            // Load image icon
            map.put(OmeroRawObjects.OmeroRawObjectType.IMAGE, ImageIO.read(getClass().getClassLoader().getResource("images/image16.png")));

            // Load screen icon
            map.put(OmeroRawObjects.OmeroRawObjectType.SCREEN, ImageIO.read(getClass().getClassLoader().getResource("images/folder_screen16.png")));

            // Load plate icon
            map.put(OmeroRawObjects.OmeroRawObjectType.PLATE, ImageIO.read(getClass().getClassLoader().getResource("images/folder_plate16.png")));

            // Load orphaned folder icon
            map.put(OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER, ImageIO.read(getClass().getClassLoader().getResource("images/folder_yellow16.png")));

            // Load image icon
            map.put(OmeroRawObjects.OmeroRawObjectType.WELL, ImageIO.read(getClass().getClassLoader().getResource("images/folder_well16.png")));

        } catch (IOException e) {
            logger.warn("Could not load OMERO icons: {}", e.getLocalizedMessage());
        }
        return map;
    }

    /**
     * Return a list of Strings representing the {@code OmeroRawObject}s in the parameter list.
     * The returned Strings are the lower level of OMERO object possible (giving a Dataset 
     * object should return Images URI as Strings). The list is filter according to the current 
     * group/owner and filter text.
     *
     * @param list of OmeroRawObjects
     * @return list of constructed Strings
     * @see OmeroRawTools#getURIs(URI,OmeroRawClient)
     */
    private List<String> getObjectsURI(OmeroRawObjects.OmeroRawObject... list) {
        List<String> URIs = new ArrayList<>();
        for (OmeroRawObjects.OmeroRawObject obj: list) {
            if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER) {
                var filteredList = filterList(((OmeroRawObjects.OrphanedFolder)obj).getImageList(), comboGroup.getSelectionModel().getSelectedItem(), comboOwner.getSelectionModel().getSelectedItem(), null);
                URIs.addAll(filteredList.stream().map(this::createObjectURI).collect(Collectors.toList()));
            } else {
                try {
                    URIs.addAll(OmeroRawTools.getURIs(URI.create(createObjectURI(obj)), client).stream().map(URI::toString).collect(Collectors.toList()));
                } catch (IOException ex) {
                    logger.error("Could not get URI for " + obj.getName() + ": {}", ex.getLocalizedMessage());
                } catch (DSOutOfServiceException | ExecutionException | DSAccessException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return URIs;
    }

    /**
     * Reconstruct the URI of the given {@code OmeroRawObject} as a String.
     * @param omeroObj
     * @return
     */
    private String createObjectURI(OmeroRawObjects.OmeroRawObject omeroObj) {
        return String.format("%s://%s%s/webclient/?show=%s-%d",
                serverURI.getScheme(),
                serverURI.getHost(),
                serverURI.getPort() > -1 ? ":" + serverURI.getPort() : "",
                omeroObj.getType().toString().toLowerCase(),
                omeroObj.getId()
        );
    }

    private static List<OmeroRawObjects.OmeroRawObject> filterList(List<OmeroRawObjects.OmeroRawObject> list, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner, String filter) {
        return list.stream()
                .filter(e -> {
                    if (group == null) return true;
                    return group == OmeroRawObjects.Group.getAllGroupsGroup() || e.getGroup().equals(group);
                })
                .filter(e -> {
                    if (owner == null) return true;
                    return owner == OmeroRawObjects.Owner.getAllMembersOwner() || e.getOwner().equals(owner);
                })
                .filter(e -> matchesSearch(e, filter))
                .collect(Collectors.toList());
    }

    private static boolean matchesSearch(OmeroRawObjects.OmeroRawObject obj, String filter) {
        if (filter == null || filter.isEmpty())
            return true;

        if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
            return true;

        if (obj.getParent().getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
            return obj.getName().toLowerCase().contains(filter.toLowerCase());

        return matchesSearch(obj.getParent(), filter);
    }

    private void refreshTree() {
        tree.setRoot(null);
        tree.refresh();
        tree.setRoot(new OmeroRawObjectTreeItem(new OmeroRawObjects.Server(serverURI)));
        tree.refresh();
    }

    private static ObservableValue<String> getObjectInfo(Integer index, OmeroRawObjects.OmeroRawObject omeroObject) {
        if (omeroObject == null)
            return new ReadOnlyObjectWrapper<>();
        String[] outString = new String[0];
        String name = omeroObject.getName();
        String id = String.valueOf(omeroObject.getId());
        String owner = omeroObject.getOwner() == null ? null : omeroObject.getOwner().getName();
        String group = omeroObject.getGroup() == null ? null : omeroObject.getGroup().getName();
        if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)
            outString = new String[] {name};
        else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT) {
            String description = ((OmeroRawObjects.Project)omeroObject).getDescription();
            if (description == null || description.isEmpty())
                description = "-";
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[] {name, id, description, owner, group, nChildren};

        } else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET) {
            String description = ((OmeroRawObjects.Dataset) omeroObject).getDescription();
            if (description == null || description.isEmpty())
                description = "-";
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[]{name, id, description, owner, group, nChildren};}

        else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.SCREEN) {
            String description = ((OmeroRawObjects.Screen)omeroObject).getDescription();
            if (description == null || description.isEmpty())
                description = "-";
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[] {name, id, description, owner, group, nChildren};}

        else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE) {
            String description = ((OmeroRawObjects.Plate)omeroObject).getDescription();
            if (description == null || description.isEmpty())
                description = "-";
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[] {name, id, description, owner, group, nChildren};}

        else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.WELL) {
            String description = ((OmeroRawObjects.Well)omeroObject).getDescription();
            if (description == null || description.isEmpty())
                description = "-";
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[] {name, id, description, owner, group, nChildren};

        } else if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
            OmeroRawObjects.Image obj = (OmeroRawObjects.Image)omeroObject;
            String acquisitionDate = obj.getAcquisitionDate() == -1 ? "-" : new Date(obj.getAcquisitionDate()).toString();
            String width = obj.getImageDimensions()[0] + " px";
            String height = obj.getImageDimensions()[1] + " px";
            String c = String.valueOf(obj.getImageDimensions()[2]);
            String z = String.valueOf(obj.getImageDimensions()[3]);
            String t = String.valueOf(obj.getImageDimensions()[4]);
            String pixelSizeX = obj.getPhysicalSizes()[0] == null ? "-" : obj.getPhysicalSizes()[0].getValue() + " " + obj.getPhysicalSizes()[0].getSymbol();
            String pixelSizeY = obj.getPhysicalSizes()[1] == null ? "-" : obj.getPhysicalSizes()[1].getValue() + " " + obj.getPhysicalSizes()[1].getSymbol();
            String pixelSizeZ = obj.getPhysicalSizes()[2] == null ? "-" : obj.getPhysicalSizes()[2].getValue() + obj.getPhysicalSizes()[2].getSymbol();
            String pixelType = obj.getPixelType();
            outString = new String[] {name, id, owner, group, acquisitionDate, width, height, c, z, t, pixelSizeX, pixelSizeY, pixelSizeZ, pixelType};
        }

        return new ReadOnlyObjectWrapper<>(outString[index]);
    }

    private void updateDescription() {
        ObservableList<Integer> indexList = FXCollections.observableArrayList();
        var selectedItems = tree.getSelectionModel().getSelectedItems();
        if (selectedItems.size() == 1 && selectedItems.get(0) != null) {
            if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)) {
                Integer[] orphanedIndices = new Integer[orphanedAttributes.length];
                for (int index = 0; index < orphanedAttributes.length; index++) orphanedIndices[index] = index;
                indexList = FXCollections.observableArrayList(orphanedIndices);
            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.PROJECT)) {
                Integer[] projectIndices = new Integer[projectAttributes.length];
                for (int index = 0; index < projectAttributes.length; index++) projectIndices[index] = index;
                indexList = FXCollections.observableArrayList(projectIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.DATASET)) {
                Integer[] datasetIndices = new Integer[datasetAttributes.length];
                for (int index = 0; index < datasetAttributes.length; index++) datasetIndices[index] = index;
                indexList = FXCollections.observableArrayList(datasetIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.SCREEN)) {
                Integer[] datasetIndices = new Integer[screenAttributes.length];
                for (int index = 0; index < screenAttributes.length; index++) datasetIndices[index] = index;
                indexList = FXCollections.observableArrayList(datasetIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.PLATE)) {
                Integer[] datasetIndices = new Integer[plateAttributes.length];
                for (int index = 0; index < plateAttributes.length; index++) datasetIndices[index] = index;
                indexList = FXCollections.observableArrayList(datasetIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.WELL)) {
                Integer[] datasetIndices = new Integer[wellAttributes.length];
                for (int index = 0; index < wellAttributes.length; index++) datasetIndices[index] = index;
                indexList = FXCollections.observableArrayList(datasetIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.IMAGE)) {
                Integer[] imageIndices = new Integer[imageAttributes.length];
                for (int index = 0; index < imageAttributes.length; index++) imageIndices[index] = index;
                indexList = FXCollections.observableArrayList(imageIndices);
            }
        }
        description.getItems().setAll(indexList);
    }

    private void clearCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /**
     * Prompt to import images, specifying the {@link OmeroRawImageServerBuilder} if possible.
     * @param validUris
     * @return
     */
    private List<ProjectImageEntry<BufferedImage>> promptToImportOmeroImages(String... validUris) {
        var builder = ImageServerProvider.getInstalledImageServerBuilders(BufferedImage.class).stream().filter(b -> b instanceof OmeroRawImageServerBuilder).findFirst().orElse(null);
        return ProjectCommands.promptToImportImages(qupath, builder, validUris);
    }


    /**
     * Paint the specified image onto the specified canvas (of the preferred size).
     * Additionally, it returns the {@code WritableImage} for further use.
     * @param img
     * @param canvas
     * @param prefSize
     * @return writable image
     */
    private static WritableImage paintBufferedImageOnCanvas(BufferedImage img, Canvas canvas, int prefSize) {
        canvas.setWidth(prefSize);
        canvas.setHeight(prefSize);

        // Color the canvas in black, in case no new image can be painted
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        if (img == null)
            return null;

        var wi =  SwingFXUtils.toFXImage(img, null);
        if (wi == null)
            return wi;

        GuiTools.paintImage(canvas, wi);
        return wi;
    }

    /**
     * Return whether the image type is supported by QuPath.
     * @param omeroObj
     * @return isSupported
     */
    // TODO check the different unsuported format or othe rthings that are not supported by Omero Raw Server
    private static boolean isSupported(OmeroRawObjects.OmeroRawObject omeroObj) {
        if (omeroObj == null || omeroObj.getType() != OmeroRawObjects.OmeroRawObjectType.IMAGE)
            return true;
        return true;//isUint8((Image)omeroObj) && has3Channels((Image)omeroObj);
    }

   /* private static boolean isUint8(Image image) {
        if (image == null)
            return false;
        return image.getPixelType().equals("uint8");
    }

    private static boolean has3Channels(Image image) {
        if (image == null)
            return false;
        return Integer.parseInt(getObjectInfo(7, image).getValue()) == 3;
    }*/

    /**
     * Set the specified item and its children to the specified expanded mode
     * @param item
     */
    private static void expandTreeView(TreeItem<OmeroRawObjects.OmeroRawObject> item){
        if (item != null && !item.isLeaf()) {
            if (!(item.getValue().getType() == OmeroRawObjects.OmeroRawObjectType.SERVER))
                item.setExpanded(true);

            for (var child: item.getChildren()) {
                expandTreeView(child);
            }
        }
    }

    /**
     * Collapse the TreeView. The {@code item} value must be an {@code OmeroRawObjectType.SERVER} (root).
     * @param item
     */
    private static void collapseTreeView(TreeItem<OmeroRawObjects.OmeroRawObject> item){
        if (item != null && !item.isLeaf() && item.getValue().getType() == OmeroRawObjects.OmeroRawObjectType.SERVER) {
            for (var child: item.getChildren()) {
                child.setExpanded(false);
            }
        }
    }

    private void updateGroupsComboBox(List<OmeroRawObjects.Group> tempGroups){
        // If we suddenly found more Groups, update the set (shoudn't happen)
        if (tempGroups.size() > groups.size()) {
            groups.clear();
            groups.addAll(tempGroups);
            // Update comboBox
            Platform.runLater(() -> {
                var selectedItem = comboGroup.getSelectionModel().getSelectedItem();
                comboGroup.getItems().setAll(groups);
                if (selectedItem == null)
                    comboGroup.getSelectionModel().selectFirst();
                else
                    comboGroup.getSelectionModel().select(selectedItem);
            });
        }
    }

    private void updateOwnersComboBox(List<OmeroRawObjects.Owner> tempOwners, OmeroRawObjects.Owner currentOwner){
        if (!tempOwners.containsAll(comboOwner.getItems()) || !comboOwner.getItems().containsAll(tempOwners)) {
            Platform.runLater(() -> {
                comboOwner.getItems().setAll(tempOwners);
                // Attempt not to change the currently selected owner if present in new Owner set
                if (tempOwners.contains(currentOwner))
                    comboOwner.getSelectionModel().select(currentOwner);
                else
                    comboOwner.getSelectionModel().selectFirst(); // 'All members'
            });
        }
    }


    /**
     * Display an OMERO object using its name.
     */
    private class OmeroObjectCell extends TreeCell<OmeroRawObjects.OmeroRawObject> {

        private final Canvas iconCanvas = new Canvas();
        private final Canvas tooltipCanvas = new Canvas();

        @Override
        public void updateItem(OmeroRawObjects.OmeroRawObject item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            // Since cells are recycled, make sure they are not disabled or transparent
            setOpacity(1.0);
            disableProperty().unbind();
            setDisable(false);
            paintBufferedImageOnCanvas(null, tooltipCanvas, 0);

            String name;
            Tooltip tooltip = new Tooltip();
            BufferedImage icon = omeroIcons.get(item.getType());
            if (item.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
                name = serverURI.getHost();
            else if (item.getType() == OmeroRawObjects.OmeroRawObjectType.PROJECT ||
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.DATASET ||
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.PLATE ||
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.SCREEN ||
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.WELL)
                name = item.getName() + " (" + item.getNChildren() + ")";
            else if (item.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER) {
                // No need for 'text', as we're using the graphic component of the cell for orphaned folder
                setText("");
                var label = new Label("", iconCanvas);

                // Bind the label property to display the total amount of loaded orphaned images (for this Group/Owner)
                label.textProperty().bind(
                        Bindings.when(orphanedFolder.getLoadingProperty())
                                .then(Bindings.concat(item.getName(), " (loading...)"))
                                .otherwise(Bindings.concat(item.getName(), " (", currentOrphanedCount, ")")));

                // If orphaned images are still loading, disable the cell (prevent weird and unnecessary errors)
                disableProperty().bind(orphanedFolder.getLoadingProperty());
                if (icon != null)
                    paintBufferedImageOnCanvas(icon, iconCanvas, 15);
                // Orphaned object is still 'selectable' via arrows (despite being disabled), which looks like a JavaFX bug..
//            		orphanedFolder.getCurrentCountProperty().addListener((v, o, n) -> getDisclosureNode().setVisible(n.intValue() > 0 && !orphanedFolder.getLoadingProperty().get()));
                tooltip.setText(item.getName());
                setTooltip(tooltip);
                setGraphic(label);
                return;
            } else if (item.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                name = item.getName();
                GridPane gp = new GridPane();
                gp.addRow(0, tooltipCanvas, new Label(name));
                if (!isSupported(item)) {
                    setOpacity(0.5);
                    Label notSupportedLabel = new Label("Image not supported:");
                    notSupportedLabel.setStyle("-fx-text-fill: red;");

                    // Clarify to the user WHY it's not supported
                    // TODO clean this code according to what is supported or not (see the other todo above the isSupported method)
                    Label uint8 = new Label();
 //                   if (isUint8((Image)item)) {
 //                       uint8.setText("- uint8 " + Character.toString((char)10003));
 //                   } else {
                        uint8.setText("- uint8 " + (char) 10007);
                        uint8.setStyle("-fx-text-fill: red;");
 //                   }
                    Label has3Channels = new Label();
 //                   if (has3Channels((Image)item)) {
  //                      has3Channels.setText("- 3 channels " + Character.toString((char)10003));
  //                  } else {
                        has3Channels.setText("- 3 channels " + (char) 10007);
                        has3Channels.setStyle("-fx-text-fill: red;");
  //                  }
                    gp.addRow(1, notSupportedLabel, new HBox(uint8, has3Channels));
                }

                tooltip.setOnShowing(e -> {
                    // Image tooltip shows the thumbnail (could show icon for other items, but icon is very low quality)
                    if (thumbnailBank.containsKey(item.getId()))
                        paintBufferedImageOnCanvas(thumbnailBank.get(item.getId()), tooltipCanvas, 100);
                    else {
                        // Get thumbnail from JSON API in separate thread
                        executorThumbnails.submit(() -> {
                            var loadedImg = OmeroRawTools.getThumbnail(client, item.getId(), imgPrefSize);
                            if (loadedImg != null) {
                                thumbnailBank.put(item.getId(), loadedImg);
                                Platform.runLater(() -> paintBufferedImageOnCanvas(loadedImg, tooltipCanvas, 100));
                            }
                        });
                    }
                });
                setText(name);
                setTooltip(tooltip);
                tooltip.setGraphic(gp);
            } else {
                name = item.getName();
                if (!isSupported(item))
                    setOpacity(0.5);
            }

            // Paint icon
            if (icon != null) {
                paintBufferedImageOnCanvas(icon, iconCanvas, 15);
                setGraphic(iconCanvas);
            }

            if (item.getType() != OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                tooltip.setText(name);
                tooltip.setGraphic(tooltipCanvas);
            }
            setTooltip(tooltip);
            setText(name);
        }
    }

    /**
     * TreeItem to help with the display of OMERO objects.
     */
    private class OmeroRawObjectTreeItem extends TreeItem<OmeroRawObjects.OmeroRawObject> {

        private boolean computed = false;

        private OmeroRawObjectTreeItem(OmeroRawObjects.OmeroRawObject obj) {
            super(obj);
        }

        /**
         * This method gets the children of the current tree item.
         * Only the currently expanded items will call this method.
         * <p>
         * If we have never seen the current tree item, a JSON request
         * will be sent to the OMERO API to get its children, this value
         * will then be stored (cached). If we have seen this tree item
         * before, it will simply return the stored value.
         *
         * All stored values are in @ {@code serverChildrenList},
         * {@code orphanedImageList}, {@code projectMap} & {@code datasetMap}.
         */
        @Override
        public ObservableList<TreeItem<OmeroRawObjects.OmeroRawObject>> getChildren() {
            if (!isLeaf() && !computed) {
                loadingChildrenLabel.setOpacity(1.0);
                var filterTemp = filter.getText();

                // If submitting tasks to a shutdown executor, an Exception is thrown
                if (executorTable.isShutdown()) {
                    loadingChildrenLabel.setOpacity(0);
                    return FXCollections.observableArrayList();
                }

                executorTable.submit(() -> {
                    var parentOmeroObj = this.getValue();

                    // get current groups and owners
                    OmeroRawObjects.Group currentGroup = comboGroup.getSelectionModel().getSelectedItem();
                    OmeroRawObjects.Owner currentOwner = comboOwner.getSelectionModel().getSelectedItem();
                    List<OmeroRawObjects.OmeroRawObject> children = new ArrayList<>();

                    if (parentOmeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER) {
                        // if selected "all members", get data from all owners
                        if(currentOwner.equals(OmeroRawObjects.Owner.getAllMembersOwner())){
                            // get all available owners
                            ObservableList<OmeroRawObjects.Owner> allUsers = comboOwner.getItems();
                            // remove "all members" owner and get data from others
                            for(OmeroRawObjects.Owner owner : allUsers.stream().filter(e->!e.equals(currentOwner)).collect(Collectors.toList()))
                                children.addAll(OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, owner));
                        }else
                            // get data from the selected owner
                            children = OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, currentOwner);

                        // get all group/owners maps
                        Map<OmeroRawObjects.Group, List<OmeroRawObjects.Owner>> newGroupOwnersMap = OmeroRawBrowserTools.getGroupUsersMapAvailableForCurrentUser(client);

                        // update groups comboBox
                        List<OmeroRawObjects.Group> newGroups = new ArrayList<>(newGroupOwnersMap.keySet()); // do nat add "All groups" => make no sense
                        OmeroRawImageServerBrowserCommand.this.updateGroupsComboBox(newGroups);

                        // update owners comboBox
                        List<OmeroRawObjects.Owner> newOwners = new ArrayList<>(newGroupOwnersMap.get(currentGroup));
                        newOwners.add(0, OmeroRawObjects.Owner.getAllMembersOwner());  // add 'All members' on the top of the list
                        OmeroRawImageServerBrowserCommand.this.updateOwnersComboBox(newOwners,currentOwner);

                        if (owners.size() == 1)
                            owners = new HashSet<>(newOwners);

                    }else if (parentOmeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER) {
                        children = orphanedImageList;
                    } else {
                        children = OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, parentOmeroObj.getOwner());
                    }

                    // convert children into Java Tree items
                    var items = filterList(children, comboGroup.getSelectionModel().getSelectedItem(), comboOwner.getSelectionModel().getSelectedItem(), filterTemp).stream()
                            .map(OmeroRawObjectTreeItem::new)
                            .collect(Collectors.toList());

                    // Add an 'Orphaned Images' tree item to the server's children
                    if (parentOmeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER && (filter == null || filterTemp.isEmpty()))
                        items.add(new OmeroRawObjectTreeItem(orphanedFolder));

                    Platform.runLater(() -> {
                        super.getChildren().setAll(items);
                        loadingChildrenLabel.setOpacity(0);
                    });

                    computed = true;
                    return super.getChildren();
                });
            }
            return super.getChildren();
        }


        @Override
        public boolean isLeaf() {
            var obj = this.getValue();
            if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
                return false;
            if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE)
                return true;
            return obj.getNChildren() == 0;
        }

        /**
         * See {@link "https://stackoverflow.com/questions/23699371/java-8-distinct-by-property"}
         * @param <T>
         * @param keyExtractor
         * @return
         */
        private <T> Predicate<T> distinctByName(Function<? super T, ?> keyExtractor) {
            Set<Object> seen = ConcurrentHashMap.newKeySet();
            return t -> seen.add(keyExtractor.apply(t));
        }
    }


    private class AdvancedObjectInfo {

        private final OmeroRawObjects.OmeroRawObject obj;
        private final OmeroRawAnnotations tags;
        private final OmeroRawAnnotations keyValuePairs;
//		private final OmeroRawAnnotations tables;
        private final OmeroRawAnnotations attachments;
        private final OmeroRawAnnotations comments;
        private final OmeroRawAnnotations ratings;
//		private final OmeroRawAnnotations others;

        private AdvancedObjectInfo(OmeroRawObjects.OmeroRawObject obj) {
            this.obj = obj;
            this.tags = OmeroRawBrowserTools.readAnnotationsItems(client, obj, OmeroRawAnnotationType.TAG);
            this.keyValuePairs = OmeroRawBrowserTools.readAnnotationsItems(client, obj, OmeroRawAnnotationType.MAP);
//			this.tables = OmeroRawTools.getOmeroAnnotations(client, obj, OmeroRawAnnotationType.TABLE);
            this.attachments = OmeroRawBrowserTools.readAnnotationsItems(client, obj, OmeroRawAnnotationType.ATTACHMENT);
            this.comments = OmeroRawBrowserTools.readAnnotationsItems(client, obj, OmeroRawAnnotationType.COMMENT);
            this.ratings = OmeroRawBrowserTools.readAnnotationsItems(client, obj, OmeroRawAnnotationType.RATING);
//			this.others = OmeroRawTools.getOmeroAnnotations(client, obj, OmeroRawAnnotationType.CUSTOM);

            showOmeroObjectInfo();
        }



        private void showOmeroObjectInfo() {
            BorderPane bp = new BorderPane();
            GridPane gp = new GridPane();

            Label nameLabel = new Label(obj.getName());
            nameLabel.setStyle(BOLD);

            int row = 0;
            GridPaneUtils.addGridRow(gp, row++, 0, null, new TitledPane(obj.getType().toString() + " Details", createObjectDetailsPane(obj)));
            GridPaneUtils.addGridRow(gp, row++, 0, null, createAnnotationsPane("Tags (" + tags.getSize() + ")", tags));
            GridPaneUtils.addGridRow(gp, row++, 0, null, createAnnotationsPane("Key-Value Pairs (" + keyValuePairs.getSize() + ")", keyValuePairs));
//			GridPaneUtils.addGridRow(gp, row++, 0, "Tables", new TitledPane("Tables", createAnnotationsPane(tables)));
            GridPaneUtils.addGridRow(gp, row++, 0, null, createAnnotationsPane("Attachments (" + attachments.getSize() + ")", attachments));
            GridPaneUtils.addGridRow(gp, row++, 0, null, createAnnotationsPane("Comments (" + comments.getSize() + ")", comments));
            GridPaneUtils.addGridRow(gp, row++, 0, "Ratings", createAnnotationsPane("Ratings (" + ratings.getSize() + ")", ratings));
//			GridPaneUtils.addGridRow(gp, row++, 0, "Others", new TitledPane("Others (" + others.getSize() + ")", createAnnotationsPane(others)));

            // Top: object name
            bp.setTop(nameLabel);

            // Center: annotations
            bp.setCenter(gp);

            // Set max width/height
            bp.setMaxWidth(500.0);
            bp.setMaxHeight(800.0);

            var dialog = Dialogs.builder()
                    .content(bp)
                    .title("More info")
                    .build();

            final Window window = dialog.getDialogPane().getScene().getWindow();
            window.addEventHandler(WindowEvent.WINDOW_SHOWN, new EventHandler<WindowEvent>() {
                @Override
                public void handle(WindowEvent event) {
                    Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
                    if(window.getHeight() > screenBounds.getHeight()) {
                        window.setY(0);
                    }
                }
            });

            // Resize Dialog when expanding/collapsing any TitledPane
            gp.getChildren().forEach(e -> {
                if (e instanceof TitledPane)
                    ((TitledPane)e).heightProperty().addListener((v, o, n) -> dialog.getDialogPane().getScene().getWindow().sizeToScene());
            });

            // Catch escape key pressed
            dialog.getDialogPane().getScene().addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE)
                    ((Stage)dialog.getDialogPane().getScene().getWindow()).close();
            });

            dialog.showAndWait();
        }

        /*
         * Create a ScrollPane in which each row is an annotation value
         */
        private Node createAnnotationsPane(String title, OmeroRawAnnotations omeroRawAnnotations) {
            TitledPane tp = new TitledPane();
            tp.setText(title);

            if (omeroRawAnnotations == null ||
                    omeroRawAnnotations.getAnnotations() == null ||
                    omeroRawAnnotations.getAnnotations().isEmpty() ||
                    omeroRawAnnotations.getType() == null)
                return tp;

            ScrollPane sp = new ScrollPane();
            GridPane gp = new GridPane();
            gp.setHgap(50.0);
            gp.setVgap(1.0);
            sp.setMaxHeight(800.0);
            sp.setMaxWidth(500.0);
            sp.setMinHeight(50.0);
            sp.setMinWidth(50.0);
            sp.setPadding(new Insets(5.0, 5.0, 5.0, 5.0));

            var anns = omeroRawAnnotations.getAnnotations();
            String tooltip;
            switch (omeroRawAnnotations.getType()) {
                case TAG:
                    for (var ann: anns) {
                        var ann2 = (TagAnnotation)ann;
                        var addedBy = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.addedBy().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        var creator = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.getOwner().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        tooltip = String.format("Added by: %s%sCreated by: %s", addedBy, System.lineSeparator(), creator);
                        GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, tooltip, new Label(ann2.getValue()));
                    }
                    break;
                case MAP:
                    for (var ann: anns) {
                        var ann2 = (MapAnnotation)ann;
                        var addedBy = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.addedBy().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        var creator = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.getOwner().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        for (var value: ann2.getValues().entrySet())
                            addKeyValueToGrid(gp, true, "Added by: " + addedBy + System.lineSeparator() + "Created by: " + creator, value.getKey(), value.getValue().isEmpty() ? "-" : value.getValue());
                    }
                    break;
                case ATTACHMENT:
                    for (var ann: anns) {
                        var ann2 = (FileAnnotation)ann;
                        var addedBy = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.addedBy().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        var creator = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.getOwner().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        tooltip = String.format("Added by: %s%sCreated by: %s%sType: %s", addedBy, System.lineSeparator(), creator, System.lineSeparator(), ann2.getMimeType());
                        GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, tooltip, new Label(ann2.getFilename() + " (" + ann2.getFileSize() + " bytes)"));
                    }
                    break;
                case COMMENT:
                    for (var ann: anns) {
                        var ann2 = (CommentAnnotation)ann;
                        var addedBy = omeroRawAnnotations.getExperimenters().parallelStream()
                                .filter(e -> e .getId() == ann2.addedBy().getId())
                                .map(OmeroRawObjects.Experimenter::getFullName)
                                .findAny().get();
                        GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, "Added by " + addedBy, new Label(ann2.getValue()));
                    }
                    break;
                case RATING:
                    int rating = 0;
                    for (var ann: anns) {
                        var ann2 = (LongAnnotation)ann;
                        rating += ann2.getValue();
                    }

                    for (int i = 0; i < Math.round(rating/anns.size()); i++)
                        gp.add(GlyphFontRegistry
                                .font("icomoon") // font style of the icon
                                .create("\u2605") // icon type (a star)
                                .size(QuPathGUI.TOOLBAR_ICON_SIZE) // size of the icon
                                .color(javafx.scene.paint.Color.GRAY), // color the icon
                                i, 0);
                    gp.setHgap(10.0);
                    break;
                default:
                    logger.error("OMERO annotation not supported: {}", omeroRawAnnotations.getType());
            }

            sp.setContent(gp);
            tp.setContent(sp);
            return tp;
        }

        private Node createObjectDetailsPane(OmeroRawObjects.OmeroRawObject obj) {
            GridPane gp = new GridPane();

            addKeyValueToGrid(gp, true, "Id", "Id", String.valueOf(obj.getId()));
            addKeyValueToGrid(gp, true, "Owner", "Owner", obj.getOwner().getName());
            addKeyValueToGrid(gp, false, "Group", "Group", obj.getGroup().getName());

            if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                OmeroRawObjects.Image temp = (OmeroRawObjects.Image)obj;

                gp.add(new Separator(), 0, gp.getRowCount() + 1, gp.getColumnCount(), 1);
                String acquisitionDate = temp.getAcquisitionDate() == -1 ? "-" : new Date(temp.getAcquisitionDate()).toString();
                String pixelSizeX = temp.getPhysicalSizes()[0] == null ? "-" : temp.getPhysicalSizes()[0].getValue() + " " + temp.getPhysicalSizes()[0].getSymbol();
                String pixelSizeY = temp.getPhysicalSizes()[1] == null ? "-" : temp.getPhysicalSizes()[1].getValue() + " " + temp.getPhysicalSizes()[1].getSymbol();
                String pixelSizeZ = temp.getPhysicalSizes()[2] == null ? "-" : temp.getPhysicalSizes()[2].getValue() + temp.getPhysicalSizes()[2].getSymbol();

                addKeyValueToGrid(gp, true, "Acquisition date", "Acquisition date", acquisitionDate);
                addKeyValueToGrid(gp, true, "Image width", "Image width", temp.getImageDimensions()[0] + " px");
                addKeyValueToGrid(gp, true, "Image height", "Image height", temp.getImageDimensions()[1] + " px");
                addKeyValueToGrid(gp, true, "Num. channels", "Num. channels", String.valueOf(temp.getImageDimensions()[2]));
                addKeyValueToGrid(gp, true, "Num. z-slices", "Num. z-slices", String.valueOf(temp.getImageDimensions()[3]));
                addKeyValueToGrid(gp, true, "Num. timepoints", "Num. timepoints", String.valueOf(temp.getImageDimensions()[4]));
                addKeyValueToGrid(gp, true, "Pixel size X", "Pixel size X", pixelSizeX);
                addKeyValueToGrid(gp, true, "Pixel size Y", "Pixel size Y", pixelSizeY);
                addKeyValueToGrid(gp, true, "Pixel size Z", "Pixel size Z", pixelSizeZ);
                addKeyValueToGrid(gp, false, "Pixel type", "Pixel type", temp.getPixelType());
            }

            gp.setHgap(50.0);
            gp.setVgap(1.0);
            return gp;
        }


        /**
         * Append a key-value row to the end (bottom row) of the specified GridPane.
         * @param gp
         * @param addSeparator
         * @param key
         * @param value
         */
        private void addKeyValueToGrid(GridPane gp, boolean addSeparator, String tooltip, String key, String value) {
            Label keyLabel = new Label(key);
            keyLabel.setStyle(BOLD);
            int row = gp.getRowCount();

            GridPaneUtils.addGridRow(gp, row, 0, tooltip, keyLabel, new Label(value));
            if (addSeparator)
                gp.add(new Separator(), 0, row + 1, gp.getColumnCount(), 1);
        }
    }

    private class AdvancedSearch {

        private final TableView<SearchResult> resultsTableView = new TableView<>();
        private final ObservableList<SearchResult> obsResults = FXCollections.observableArrayList();

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

        private AdvancedSearch() {

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
            ownedByCombo.setConverter(ownerStringConverter);

            // Changing the ComboBox value refreshes the TreeView
            groupCombo.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
                Platform.runLater(() -> {
                    Map<OmeroRawObjects.Group, List<OmeroRawObjects.Owner>> tempMap = null;
                    tempMap = OmeroRawBrowserTools.getGroupUsersMapAvailableForCurrentUser(client);

                    var tempOwners = new ArrayList<>(tempMap.get(groupCombo.getSelectionModel().getSelectedItem()));
                    tempOwners.add(0, OmeroRawObjects.Owner.getAllMembersOwner());
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
                executorQuery.submit(() -> searchQuery());

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
                String[] URIs = resultsTableView.getSelectionModel().getSelectedItems().stream()
                        .flatMap(item -> {
                            try {
                                return OmeroRawTools.getURIs(item.link.toURI(), client).stream();
                            } catch (URISyntaxException | IOException ex) {
                                logger.error("Error while opening " + item.name + ": {}", ex.getLocalizedMessage());
                            } catch (DSOutOfServiceException | ExecutionException | DSAccessException ex) {
                                throw new RuntimeException(ex);
                            }
                            return null;
                        })
                        .map(URI::toString)
                        .toArray(String[]::new);
                if (URIs.length > 0) {
                    HashSet<ProjectImageEntry<BufferedImage>> beforeImport = new HashSet<>(qupath.getProject().getImageList());
                    promptToImportOmeroImages(URIs);
                    HashSet<ProjectImageEntry<BufferedImage>> afterImport = new HashSet<>(qupath.getProject().getImageList());

                    // filter newly imported images
                    afterImport.removeAll(beforeImport);

                    //TODO Find a way to get an OmeroRawObjects.OmeroRawObject
                    /*for(ProjectImageEntry<BufferedImage> entry : afterImport) {
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
                        var wi = paintBufferedImageOnCanvas(img, canvas, prefScale);
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
                    var selectedItem = resultsTableView.getSelectionModel().getSelectedItem();
                    if (selectedItem != null) {
                        try {
                            List<URI> URIs = OmeroRawTools.getURIs(selectedItem.link.toURI(), client);
                            var uriStrings = URIs.parallelStream().map(URI::toString).toArray(String[]::new);
                            if (URIs.size() > 0) {
                                HashSet<ProjectImageEntry<BufferedImage>> beforeImport = new HashSet<>(qupath.getProject().getImageList());
                                promptToImportOmeroImages(uriStrings);
                                HashSet<ProjectImageEntry<BufferedImage>> afterImport = new HashSet<>(qupath.getProject().getImageList());

                                // filter newly imported images
                                afterImport.removeAll(beforeImport);

                                //TODO Find a way to get an OmeroRawObjects.OmeroRawObject
                               /* for(ProjectImageEntry<BufferedImage> entry : afterImport)
                                    OmeroRawBrowserTools.addContainersAsMetadataFields(client, entry);*/
                            }
                            else
                                Dialogs.showErrorMessage("No image found", "No image found in OMERO object.");
                        } catch (IOException | URISyntaxException ex) {
                            logger.error("Error while importing " + selectedItem.name + ": {}", ex.getLocalizedMessage());
                        } catch (DSOutOfServiceException | ExecutionException | DSAccessException ex) {
                            throw new RuntimeException(ex);
                        }
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
        private void searchQuery() {
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
                results = parseHTML(response);

            populateThumbnailBank(results);
            updateTableView(results);

        }

        private List<SearchResult> parseHTML(String response) {
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
                    SearchResult obj = new SearchResult(values);
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
        private void populateThumbnailBank(List<SearchResult> results) {

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
    }


    private class SearchResult {
        private final String type;
        private final int id;
        private final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        private final String name;
        private final Date acquired;
        private final Date imported;
        private final String group;
        private final URL link;

        private SearchResult(String[] values) throws ParseException, MalformedURLException {
            this.type = values[0];
            this.id = Integer.parseInt(values[1]);
            this.name = values[2];
            this.acquired = dateFormat.parse(values[3]);
            this.imported = dateFormat.parse(values[4]);
            this.group = values[5];
            this.link = URI.create(serverURI.getScheme() + "://" + serverURI.getHost() + values[6]).toURL();
        }
    }

    /**
     * Request closure of the dialog
     */
    void requestClose() {
        if (dialog != null)
            dialog.fireEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    /**
     * Shutdown the pool that loads OMERO objects' children (treeView)
     */
    void shutdownPools() {
        executorTable.shutdownNow();
        executorThumbnails.shutdownNow();
    }
}