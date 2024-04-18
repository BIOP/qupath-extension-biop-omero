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

package qupath.ext.biop.servers.omero.raw.browser;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.stream.Collectors;

import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;

import fr.igred.omero.meta.GroupWrapper;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import omero.gateway.exception.DSAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.OmeroRawExtension;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.common.LogTools;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.projects.ProjectImageEntry;

/**
 * Command to browse a specified OMERO server and import images from that server
 *
 * @author Rémy Dornier
 *
 * Based on the initial work of
 * @author Melvin Gelbard
 */
public class OmeroRawImageServerBrowserCommand implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawImageServerBrowserCommand.class);
    private static final String BOLD = "-fx-font-weight: bold";
    private final QuPathGUI qupath;
    private Stage dialog;

    // OmeroRawClient with server to browse
    private final OmeroRawClient client;
    private final URI serverURI;

    // GUI left
    private ComboBox<OmeroRawObjects.Owner> comboOwner;
    private ComboBox<OmeroRawObjects.Group> comboGroup;
    private TreeView<OmeroRawObjects.OmeroRawObject> tree;
    private TextField filter;

    // GUI right
    private TableView<Integer> description;
    private Canvas canvas;
    private final int imgPrefSize = 256;

    // GUI top and down
    private Label loadingChildrenLabel;
    private Label loadingThumbnailLabel;

    // Other
    private Map<OmeroRawObjects.OmeroRawObjectType, BufferedImage> omeroIcons;
    private ExecutorService executorTable;		// Get TreeView item children in separate thread
    private ExecutorService executorThumbnails;	// Get image thumbnails in separate thread

    // Browser data 'storage'
    private Map<OmeroRawObjects.Group, Map<OmeroRawObjects.Owner, List<OmeroRawObjects.OmeroRawObject>>> groupOwnersChildrenMap;
    private Map<OmeroRawObjects.Group, List<OmeroRawObjects.Owner>> groupMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> projectMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> screenMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> plateMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> wellMap;
    private Map<OmeroRawObjects.OmeroRawObject, List<OmeroRawObjects.OmeroRawObject>> datasetMap;
    private Map<OmeroRawObjects.Owner, List<OmeroRawObjects.OmeroRawObject>> orphanedFolderMap;
    private Map<Long, BufferedImage> thumbnailBank;

    private final String[] orphanedAttributes = new String[] {
            "Name",
            "Description",
            "Num. images"};
    private final String[] projectAttributes = new String[] {
            "Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. datasets"};
    private final String[] datasetAttributes = new String[] {
            "Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. images"};
    private final String[] screenAttributes = new String[] {
            "Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. plates"};
    private final String[] plateAttributes = new String[] {
            "Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. wells"};
    private final String[] wellAttributes = new String[] {
            "Name",
            "Id",
            "Description",
            "Owner",
            "Group",
            "Num. images"};
    private final String[] imageAttributes = new String[] {
            "Name",
            "Id",
            "Description",
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

    public OmeroRawImageServerBrowserCommand(QuPathGUI qupath, OmeroRawClient client) {
        this.qupath = qupath;
        this.client = Objects.requireNonNull(client);
        this.serverURI = client.getServerURI();
    }

    public Stage getStage() {
        return dialog;
    }

    @Override
    public void run() {
        boolean loggedIn = true;
        if (!client.isLoggedIn())
            loggedIn = client.logIn();

        if (!loggedIn)
            return;

        /*
         *
         * Initialisation
         *
         */
        groupOwnersChildrenMap = new ConcurrentHashMap<>();
        thumbnailBank = new ConcurrentHashMap<>();
        projectMap = new ConcurrentHashMap<>();
        orphanedFolderMap = new ConcurrentHashMap<>();
        screenMap = new ConcurrentHashMap<>();
        plateMap = new ConcurrentHashMap<>();
        wellMap = new ConcurrentHashMap<>();
        datasetMap = new ConcurrentHashMap<>();
        executorTable = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("children-loader", true));
        executorThumbnails = Executors.newFixedThreadPool(PathPrefs.numCommandThreadsProperty().get(), ThreadTools.createThreadFactory("thumbnail-loader", true));

        tree = new TreeView<>();
        comboGroup = new ComboBox<>();
        comboOwner = new ComboBox<>();
        filter = new TextField();

        // define the progress indicators
        ProgressIndicator progressChildren = new ProgressIndicator();
        progressChildren.setPrefSize(15, 15);
        loadingChildrenLabel = new Label("Loading OMERO objects", progressChildren);

        ProgressIndicator progressThumbnail = new ProgressIndicator();
        progressThumbnail.setPrefSize(15, 15);
        loadingThumbnailLabel = new Label("Loading thumbnail", progressThumbnail);
        loadingThumbnailLabel.setOpacity(0.0);

        // Info about the server to display at the top
        Label hostLabel = new Label(serverURI.getHost());
        Label usernameLabel = new Label();

        usernameLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            if (client.getUsername().isEmpty() && client.isLoggedIn())
                return "public";
            return client.getUsername();
        }, client.usernameProperty(), client.logProperty()));

        // 'Num of open images' text and number are bound to the size of client observable list
        Label nOpenImagesText = new Label();
        Label nOpenImages = new Label();
        nOpenImagesText.textProperty().bind(Bindings.createStringBinding(() -> "Open image" + (client.getURIs().size() > 1 ? "s" : "") + ": ", client.getURIs()));
        nOpenImages.textProperty().bind(Bindings.concat(Bindings.size(client.getURIs()), ""));
        hostLabel.setStyle(BOLD);
        usernameLabel.setStyle(BOLD);
        nOpenImages.setStyle(BOLD);

        // create the color disc showing whether if the client is connected or not
        Label isReachable = new Label();
        isReachable.graphicProperty().bind(Bindings.createObjectBinding(() -> OmeroRawBrowserTools.createStateNode(client.isLoggedIn()), client.logProperty()));

        // Get OMERO icons (project and dataset icons)
        omeroIcons = OmeroRawBrowserTools.getOmeroIcons();

        /*
         *
         * handle groups / users in combo box
         *
         */
        // get all the groups <> users
        groupMap = OmeroRawBrowserTools.getAvailableGroupUsersMap(client);

        // get default user / group object
        ExperimenterWrapper loggedInUser = client.getLoggedInUser();
        GroupWrapper userGroup;
        try{
            userGroup = client.getSimpleClient().getGroup(client.getSimpleClient().getCurrentGroupId());
        }catch(Exception e) {
            userGroup = loggedInUser.getDefaultGroup();
        }
        OmeroRawObjects.Group defaultGroup = new OmeroRawObjects.Group(userGroup, userGroup.getId(), userGroup.getName());
        OmeroRawObjects.Owner defaultOwner = new OmeroRawObjects.Owner(loggedInUser);

        // initialize group combo box
        comboGroup.getItems().setAll(groupMap.keySet());
        comboGroup.getSelectionModel().select(defaultGroup);

        // initialize owner combo box
        comboOwner.getItems().setAll(groupMap.get(comboGroup.getSelectionModel().getSelectedItem()));
        comboOwner.getSelectionModel().select(defaultOwner);
        comboOwner.setConverter(OmeroRawBrowserTools.getOwnerStringConverter(comboOwner.getItems()));

        // Changing the ComboBox value refreshes the TreeView and the available owners
        comboOwner.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> refreshTree());
        comboGroup.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            OmeroRawObjects.Group group = comboGroup.getSelectionModel().getSelectedItem();
            List<OmeroRawObjects.Owner> newOwners = new ArrayList<>(groupMap.get(group));

            // switch the client to the current group
            if(this.client.getSimpleClient().getCurrentGroupId() != group.getId())
                this.client.switchGroup(group.getId());

            updateOwnersComboBox(newOwners, comboOwner.getSelectionModel().getSelectedItem());
            refreshTree();
        });


        /*
         *
         * Create the root hierarchy
         *
         */
        OmeroRawObjectTreeItem root = new OmeroRawObjectTreeItem(new OmeroRawObjects.Server(serverURI));
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(n -> new OmeroObjectCell());


        /*
         *
         * Handle image import when double-clicking
         *
         */
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
                        // import images to QuPath
                        List<ProjectImageEntry<BufferedImage>> importedImageEntries =
                                OmeroRawBrowserTools.promptToImportOmeroImages(qupath, createObjectURI(selectedItem));

                        // add OMERO metadata fields
                        for(ProjectImageEntry<BufferedImage> entry : importedImageEntries)
                            addMetadataFieldsFromOmero(entry, selectedItem);
                    }
                }
            }
        });


        /*
         *
         * Handle Right click on container in the browser tree
         *
         */
        MenuItem moreInfoItem = new MenuItem("More info...");
        MenuItem openBrowserItem = new MenuItem("Open in browser");
        MenuItem clipboardItem = new MenuItem("Copy to clipboard");
        MenuItem collapseItem = new MenuItem("Collapse all items");

        // 'More info..' will open new AdvancedObjectInfo pane
        moreInfoItem.setOnAction(ev -> new AdvancedObjectInfo(tree.getSelectionModel().getSelectedItem().getValue(), client));
        moreInfoItem.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull()
                .or(Bindings.size(tree.getSelectionModel().getSelectedItems()).isNotEqualTo(1)
                        .or(Bindings.createBooleanBinding(() -> tree.getSelectionModel().getSelectedItem() != null && tree.getSelectionModel().getSelectedItem().getValue().getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER,
                                tree.getSelectionModel().selectedItemProperty()))));

        // Opens the OMERO object in a browser
        openBrowserItem.setOnAction(ev -> {
            var selected = tree.getSelectionModel().getSelectedItems();
            if (selected != null && selected.size() == 1) {
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
                Dialogs.showInfoNotification("Copy URI to clipboard", "URI" + (uris.size() > 1 ? "s " : " ") + "successfully copied to clipboard");
            } else
                Dialogs.showWarningNotification("Copy URI to clipboard", "The item needs to be selected first!");
        });

        // Collapse all items in the tree
        collapseItem.setOnAction(ev -> collapseTreeView(tree.getRoot()));

        // Add the items to the context menu
        tree.setContextMenu(new ContextMenu(moreInfoItem, openBrowserItem, clipboardItem, collapseItem));

        /*
         *
         * Handle container description in the right panel
         *
         */
        description = new TableView<>();
        TableColumn<Integer, String> attributeCol = new TableColumn<>("Attribute");
        TableColumn<Integer, String> valueCol = new TableColumn<>("Value");

        // Set the width of the columns to half the table's width each
        attributeCol.prefWidthProperty().bind(description.widthProperty().divide(4));
        valueCol.prefWidthProperty().bind(description.widthProperty().multiply(0.75));

        // add description attributes (left column)
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

        // add description values (right column)
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


        /*
         *
         * Handle selection on the main browser tree
         *
         */
        // listener on the selected item in the browser (i.e. when you click on a container / image)
        tree.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
            clearCanvas();
            if (n != null) {
                if (description.getPlaceholder() == null)
                    description.setPlaceholder(new Label("Multiple elements selected"));
                ObservableList<TreeItem<OmeroRawObjects.OmeroRawObject>> selectedItems = tree.getSelectionModel().getSelectedItems();

                updateDescription();
                if (selectedItems.size() == 1) {
                    OmeroRawObjects.OmeroRawObject selectedObjectLocal = n.getValue();
                    if (selectedItems.get(0) != null && selectedItems.get(0).getValue().getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                        // Check if thumbnail was previously cached, get it and show it
                        if (thumbnailBank.containsKey(selectedObjectLocal.getId()))
                            paintBufferedImageOnCanvas(thumbnailBank.get(selectedObjectLocal.getId()), canvas, imgPrefSize);
                        else {
                            // Get thumbnail from OMERO in separate thread
                            loadingThumbnailLabel.setOpacity(1.0);
                            executorThumbnails.submit(() -> {
                                // Note: it is possible that another task for the same id exists, but it
                                // shouldn't cause inconsistent results anyway, since '1 id = 1 thumbnail'
                                BufferedImage img;
                                try {
                                    img = client.getSimpleClient().getImage(selectedObjectLocal.getId()).getThumbnail(client.getSimpleClient(), imgPrefSize);
                                } catch (Exception e) {
                                    img = OmeroRawTools.readLocalImage(Utils.NO_IMAGE_THUMBNAIL);
                                }

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


        /*
         *
         * Handle root items filtering
         *
         */
        filter.setPromptText("Filter projects names");
        filter.textProperty().addListener((v, o, n) -> {
            refreshTree();
            if (n.isEmpty())
                collapseTreeView(tree.getRoot());
            else
                expandTreeView(tree.getRoot());
        });

       /*Button advancedSearchBtn = new Button("Advanced...");
        advancedSearchBtn.setOnAction(e -> new AdvancedSearch(qupath, client, owners, groups, omeroIcons, thumbnailBank, imgPrefSize));*/

        /*
         *
         * Handle the image import
         *
         */
        Button importBtn = new Button("Import image");

        // Text on button will change according to OMERO object selected
        importBtn.textProperty().bind(Bindings.createStringBinding(() -> {
            ObservableList<TreeItem<OmeroRawObjects.OmeroRawObject>> selected = tree.getSelectionModel().getSelectedItems();
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
            // get the selected image / container
            ObservableList<TreeItem<OmeroRawObjects.OmeroRawObject>> selected = tree.getSelectionModel().getSelectedItems();

            // get all image Objects from the selected item
            List<OmeroRawObjects.OmeroRawObject> validObjs = selected.parallelStream()
                    .flatMap(item -> listAllImagesToImport(item.getValue(),
                            item.getValue().getGroup(),
                            item.getValue().getOwner()).parallelStream())
                    .filter(OmeroRawImageServerBrowserCommand::isSupported)
                    .collect(Collectors.toList());

            // extract each image URI
            String[] validUris = validObjs.stream()
                    .map(this::createObjectURI)
                    .toArray(String[]::new);

            if (validUris.length == 0) {
                Dialogs.showErrorMessage("No images", "No valid images found in selected item" + (selected.size() > 1 ? "s" : "") + "!");
                return;
            }

            // In case there is no project open, directly open the image in QuPath (only if ONE IMAGE is selected)
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

            // popup a GUI showing all item to import
            List<ProjectImageEntry<BufferedImage>> importedImageEntries = OmeroRawBrowserTools.promptToImportOmeroImages(qupath, validUris);

            for(ProjectImageEntry<BufferedImage> entry : importedImageEntries) {
                // get the ID of the current imported image
                String[] query = entry.getServerBuilder().getURIs().iterator().next().getQuery().split("-");
                long id = Long.parseLong(query[query.length-1]);

                // select the corresponding object
                Optional<OmeroRawObjects.OmeroRawObject> optObj = validObjs.stream()
                            .filter(obj -> obj.getId() == id)
                            .findFirst();

                // add OMERO metadata fields
                optObj.ifPresent(omeroRawObject -> {
                    addMetadataFieldsFromOmero(entry, omeroRawObject);
                });
            }
        });


        /*
         *
         * Build the interface
         *
         */
        GridPane loadingInfoPane = new GridPane();
        GridPaneUtils.addGridRow(loadingInfoPane, 0, 0, "OMERO objects are loaded in the background", loadingChildrenLabel);
        GridPaneUtils.addGridRow(loadingInfoPane, 2, 0, "Thumbnails are loaded in the background", loadingThumbnailLabel);

        GridPane serverAttributePane = new GridPane();
        serverAttributePane.addRow(0, new Label("Server: "), hostLabel, isReachable);
        serverAttributePane.addRow(1, new Label("Username: "), usernameLabel);
        serverAttributePane.addRow(2, nOpenImagesText, nOpenImages);

        BorderPane serverInfoPane = new BorderPane();
        serverInfoPane.setLeft(serverAttributePane);
        serverInfoPane.setRight(loadingInfoPane);

        GridPane searchAndAdvancedPane = new GridPane();
        GridPaneUtils.addGridRow(searchAndAdvancedPane, 0, 0, null, filter);

        GridPane browseLeftPane = new GridPane();
        GridPaneUtils.addGridRow(browseLeftPane, 0, 0, "Filter by", comboGroup, comboOwner);
        GridPaneUtils.addGridRow(browseLeftPane, 1, 0, null, tree, tree);
        GridPaneUtils.addGridRow(browseLeftPane, 2, 0, null, searchAndAdvancedPane, searchAndAdvancedPane);
        GridPaneUtils.addGridRow(browseLeftPane, 3, 0, null, importBtn, importBtn);

        canvas = new Canvas();
        canvas.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 4, 0, 1, 1);");
        description.getColumns().add(attributeCol);
        description.getColumns().add(valueCol);

        GridPane browseRightPane = new GridPane();
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
        SplitPane browsePane = new SplitPane();
        browsePane.setPrefWidth(700.0);
        browsePane.setDividerPosition(0, 0.5);

        BorderPane mainPane = new BorderPane();
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
     * @param parent
     * @param group
     * @param owner
     * @return List of available images in the selected container
     */
    private List<OmeroRawObjects.OmeroRawObject> listAllImagesToImport(OmeroRawObjects.OmeroRawObject parent, OmeroRawObjects.Group group, OmeroRawObjects.Owner owner){
        switch (parent.getType()){
            case PROJECT:
            case SCREEN:
            case PLATE:
                var temp = getChildren(parent, group, owner);
                List<OmeroRawObjects.OmeroRawObject> out = new ArrayList<>();
                for (var subTemp: temp) {
                    out.addAll(listAllImagesToImport(subTemp, group, owner));
                }
                return out;
            case DATASET:
            case WELL:
                return getChildren(parent, group, owner);
            case ORPHANED_FOLDER:
                return ((OmeroRawObjects.OrphanedFolder)parent).getImageList();
            default:
                return Collections.singletonList(parent);
        }
    }

    /**
     * Copy tags, key-values and image hierarchy from OMERO to QuPath as metadata fields
     *
     * @param entry the project entry to add the metadata to
     * @param omeroRawObject the OMERO image object
     */
    private void addMetadataFieldsFromOmero(ProjectImageEntry<BufferedImage> entry, OmeroRawObjects.OmeroRawObject omeroRawObject){
        // add image hierarchy
        OmeroRawBrowserTools.addContainersAsMetadataFields(entry, omeroRawObject);

        // add tags
        try {
            List<String> tags = omeroRawObject.getWrapper().getTags(client.getSimpleClient())
                    .stream()
                    .map(TagAnnotationWrapper::getName)
                    .collect(Collectors.toList());
            OmeroRawScripting.addTagsToQuPath(entry, tags, Utils.UpdatePolicy.UPDATE_KEYS, true);
        }catch (AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - TAGs", "Cannot get TAGs from the image '"+omeroRawObject.getId()+"'", e, true);
        }

        // add key-values
        try {
            OmeroRawScripting.addKeyValuesToQuPath(entry, omeroRawObject.getWrapper().getKeyValuePairs(client.getSimpleClient()), Utils.UpdatePolicy.UPDATE_KEYS, true);
        }catch (AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - KVPs", "Cannot get KVPs from the image '"+omeroRawObject.getId()+"'", e, true);
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
        // Check if we already have the children for this OmeroObject (avoid sending request to the server)
        if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER && groupOwnersChildrenMap.containsKey(group) && groupOwnersChildrenMap.get(group).containsKey(owner))
            return groupOwnersChildrenMap.get(group).get(owner);
        else if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER && orphanedFolderMap.containsKey(owner))
            return orphanedFolderMap.get(owner);
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

        // Read children and populate maps
        List<OmeroRawObjects.OmeroRawObject> children = OmeroRawBrowserTools.readOmeroObjectsItems(parentObj, this.client, group, owner);

        // If parentObj is a Server, add all the screens, projects, orphaned datasets to the same map
        // Orphaned images are handled separately
        if (parentObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER) {
            // update the list of already loaded items
            if(groupOwnersChildrenMap.containsKey(group))
                groupOwnersChildrenMap.get(group).put(owner, children);
            else {
                Map<OmeroRawObjects.Owner, List<OmeroRawObjects.OmeroRawObject>> ownerMap = new HashMap<>();
                ownerMap.put(owner, children);
                groupOwnersChildrenMap.put(group, ownerMap);
            }
            orphanedFolderMap.put(owner, children.stream()
                    .filter(e->e.getType().equals(OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER))
                    .map(OmeroRawObjects.OrphanedFolder.class::cast)
                    .findFirst()
                    .get()
                    .getImageList());
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


    /**
     * Return a list of Strings representing the {@code OmeroRawObject}s in the parameter list.
     * The returned Strings are the lower level of OMERO object possible (giving a Dataset 
     * object should return Images URI as Strings). The list is filter according to the current 
     * group/owner and filter text.
     *
     * @param list of OmeroRawObjects
     * @return list of constructed Strings
     * @see OmeroRawImageServerBrowserCommand#createObjectURI(OmeroRawObjects.OmeroRawObject)
     */
    private List<String> getObjectsURI(OmeroRawObjects.OmeroRawObject... list) {
        List<String> URIs = new ArrayList<>();
        for (OmeroRawObjects.OmeroRawObject obj: list) {
            if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER) {
                var filteredList = filterList(((OmeroRawObjects.OrphanedFolder)obj).getImageList(),
                        comboGroup.getSelectionModel().getSelectedItem(), comboOwner.getSelectionModel().getSelectedItem(), null);
                URIs.addAll(filteredList.stream().map(this::createObjectURI).collect(Collectors.toList()));
            } else{
                List<OmeroRawObjects.OmeroRawObject> flatImagesList = listAllImagesToImport(obj,
                        obj.getGroup(),
                        obj.getOwner());
                URIs.addAll(flatImagesList.stream().map(this::createObjectURI).collect(Collectors.toList()));
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

    /**
     * return the filtered list of objects according to the user, group and user-defined filter of each object.
     *
     * @param list
     * @param group
     * @param owner
     * @param filter
     * @return
     */
    private static List<OmeroRawObjects.OmeroRawObject> filterList(List<OmeroRawObjects.OmeroRawObject> list,
                                                                   OmeroRawObjects.Group group, OmeroRawObjects.Owner owner,
                                                                   String filter) {
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

    /**
     * return the filtered list of objects according to the user-defined filter text.
     * @param obj
     * @param filter
     * @return
     */
    private static boolean matchesSearch(OmeroRawObjects.OmeroRawObject obj, String filter) {
        if (filter == null || filter.isEmpty())
            return true;

        if (obj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
            return true;

        if (obj.getParent().getType() == OmeroRawObjects.OmeroRawObjectType.SERVER)
            return obj.getName().toLowerCase().contains(filter.toLowerCase());

        return matchesSearch(obj.getParent(), filter);
    }

    /**
     * update the main browser tree
     */
    private void refreshTree() {
        tree.setRoot(null);
        tree.refresh();
        tree.setRoot(new OmeroRawObjectTreeItem(new OmeroRawObjects.Server(serverURI)));
        tree.refresh();
    }

    /**
     * read the object info. Special case for orphaned folder (nothing to display) and for images (raw file info).
     * All other containers have the same info fields.
     */
    private static ObservableValue<String> getObjectInfo(Integer index, OmeroRawObjects.OmeroRawObject omeroObject) {
        if (omeroObject == null)
            return new ReadOnlyObjectWrapper<>();
        String[] outString = new String[0];

        // get all common attributes
        String name = omeroObject.getName();
        String id = String.valueOf(omeroObject.getId());
        String owner = omeroObject.getOwner() == null ? null : omeroObject.getOwner().getName();
        String group = omeroObject.getGroup() == null ? null : omeroObject.getGroup().getName();
        String description = omeroObject.getDescription() == null ? "-" : omeroObject.getDescription();

        // special cases
        if (omeroObject.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER){
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[]{name, description, nChildren};
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
            String pixelSizeZ = obj.getPhysicalSizes()[2] == null ? "-" : obj.getPhysicalSizes()[2].getValue() + " " + obj.getPhysicalSizes()[2].getSymbol();
            String pixelType = obj.getPixelType();
            outString = new String[] {name, id, description, owner, group, acquisitionDate, width, height, c, z, t, pixelSizeX, pixelSizeY, pixelSizeZ, pixelType};
        } else if(omeroObject.getType() != OmeroRawObjects.OmeroRawObjectType.UNKNOWN){
            String nChildren = String.valueOf(omeroObject.getNChildren());
            outString = new String[] {name, id, description, owner, group, nChildren};
        }

        return new ReadOnlyObjectWrapper<>(outString[index]);
    }

    /**
     * Update the container metadata (right panel and under the thumbnail preview for the image)
     */
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
                Integer[] screenIndices = new Integer[screenAttributes.length];
                for (int index = 0; index < screenAttributes.length; index++) screenIndices[index] = index;
                indexList = FXCollections.observableArrayList(screenIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.PLATE)) {
                Integer[] plateIndices = new Integer[plateAttributes.length];
                for (int index = 0; index < plateAttributes.length; index++) plateIndices[index] = index;
                indexList = FXCollections.observableArrayList(plateIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.WELL)) {
                Integer[] wellIndices = new Integer[wellAttributes.length];
                for (int index = 0; index < wellAttributes.length; index++) wellIndices[index] = index;
                indexList = FXCollections.observableArrayList(wellIndices);

            } else if (selectedItems.get(0).getValue().getType().equals(OmeroRawObjects.OmeroRawObjectType.IMAGE)) {
                Integer[] imageIndices = new Integer[imageAttributes.length];
                for (int index = 0; index < imageAttributes.length; index++) imageIndices[index] = index;
                indexList = FXCollections.observableArrayList(imageIndices);
            }
        }
        description.getItems().setAll(indexList);
    }

    /**
     * clear the previous right panel (teh one with the description)
     */
    private void clearCanvas() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /**
     * Paint the specified image onto the specified canvas (of the preferred size).
     * Additionally, it returns the {@code WritableImage} for further use.
     * @param img
     * @param canvas
     * @param prefSize
     * @return writable image
     */
    protected static WritableImage paintBufferedImageOnCanvas(BufferedImage img, Canvas canvas, int prefSize) {
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
    private static boolean isSupported(OmeroRawObjects.OmeroRawObject omeroObj) {
        return !(omeroObj == null || omeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.UNKNOWN);
    }

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

    /**
     * Update the name of owners according to the selected group
     * @param tempOwners
     * @param currentOwner
     */
    private void updateOwnersComboBox(List<OmeroRawObjects.Owner> tempOwners, OmeroRawObjects.Owner currentOwner){
        if (!new HashSet<>(tempOwners).containsAll(comboOwner.getItems()) || !new HashSet<>(comboOwner.getItems()).containsAll(tempOwners)) {
            Platform.runLater(() -> {
                comboOwner.getItems().setAll(tempOwners);
                // Attempt not to change the currently selected owner if present in new Owner set
                if (tempOwners.contains(currentOwner))
                    comboOwner.getSelectionModel().select(currentOwner);
                else
                    comboOwner.getSelectionModel().selectLast(); // 'All members'
            });
        }
    }


    /**
     * Request closure of the dialog
     */
    public void requestClose() {
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


    /**
     * This class builds the GUI layout for one OMERO object in the browser
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
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.WELL ||
                    item.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER)
                name = item.getName() + " (" + item.getNChildren() + ")";
            else if (item.getType() == OmeroRawObjects.OmeroRawObjectType.IMAGE) {
                name = item.getName();
                GridPane gp = new GridPane();
                gp.addRow(0, tooltipCanvas, new Label(name));
                if (!isSupported(item)) {
                    setOpacity(0.5);
                    Label notSupportedLabel = new Label("Image not supported:");
                    notSupportedLabel.setStyle("-fx-text-fill: red;");

                    Label uint8 = new Label();
                        uint8.setText("- uint8 " + (char) 10007);
                        uint8.setStyle("-fx-text-fill: red;");
                    Label has3Channels = new Label();
                        has3Channels.setText("- 3 channels " + (char) 10007);
                        has3Channels.setStyle("-fx-text-fill: red;");
                    gp.addRow(1, notSupportedLabel, new HBox(uint8, has3Channels));
                }

                tooltip.setOnShowing(e -> {
                    // Image tooltip shows the thumbnail (could show icon for other items, but icon is very low quality)
                    if (thumbnailBank.containsKey(item.getId()))
                        paintBufferedImageOnCanvas(thumbnailBank.get(item.getId()), tooltipCanvas, 100);
                    else {
                        // Get thumbnail from OMERO in separate thread
                        executorThumbnails.submit(() -> {
                            BufferedImage loadedImg;
                            try {
                                loadedImg = client.getSimpleClient().getImage(item.getId()).getThumbnail(client.getSimpleClient(), imgPrefSize);
                            } catch (Exception e2) {
                                loadedImg = OmeroRawTools.readLocalImage(Utils.NO_IMAGE_THUMBNAIL);
                            }
                            if (loadedImg != null) {
                                thumbnailBank.put(item.getId(), loadedImg);
                                BufferedImage finalLoadedImg = loadedImg;
                                Platform.runLater(() -> paintBufferedImageOnCanvas(finalLoadedImg, tooltipCanvas, 100));
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
     * Abstract object that handle the hierarchy logic
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
                String filterTemp = filter == null ? "" : filter.getText();

                // If submitting tasks to a shutdown executor, an Exception is thrown
                if (executorTable.isShutdown()) {
                    loadingChildrenLabel.setOpacity(0);
                    return FXCollections.observableArrayList();
                }

                // retrieve children in another thread
                executorTable.submit(() -> {
                    OmeroRawObjects.OmeroRawObject parentOmeroObj = this.getValue();

                    // get current groups and owners
                    OmeroRawObjects.Group currentGroup = comboGroup.getSelectionModel().getSelectedItem();
                    OmeroRawObjects.Owner currentOwner = comboOwner.getSelectionModel().getSelectedItem();
                    List<OmeroRawObjects.OmeroRawObject> children = new ArrayList<>();

                    if (parentOmeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.SERVER) {
                        // if selected "all members", get data from all owners
                        if(currentOwner.equals(OmeroRawObjects.Owner.getAllMembersOwner())){
                            // get all available owners
                            List<OmeroRawObjects.Owner> allUsers = new ArrayList<>(comboOwner.getItems());
                            allUsers.remove(currentOwner);
                            // remove "all members" owner and get data from others
                            for(OmeroRawObjects.Owner owner : allUsers)
                                children.addAll(OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, owner));

                            // sort the project/dataset by categories and in alphabetic order
                            children.sort(Comparator.comparing(OmeroRawObjects.OmeroRawObject::getType)
                                    .thenComparing(OmeroRawObjects.OmeroRawObject::getName));

                            List<OmeroRawObjects.OrphanedFolder> orphanedFolders = children.stream()
                                    .filter(e -> e.getType().equals(OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER))
                                    .map(OmeroRawObjects.OrphanedFolder.class::cast)
                                    .collect(Collectors.toList());

                            OmeroRawObjects.OrphanedFolder sharedOrphanedFolder = new OmeroRawObjects.OrphanedFolder(parentOmeroObj,
                                    currentOwner.getWrapper(), currentGroup.getWrapper());
                            orphanedFolders.forEach(e->sharedOrphanedFolder.addOrphanedImages(e.getImageList()));
                            children.removeAll(orphanedFolders);
                            children.add(sharedOrphanedFolder);

                        }else
                            // get data from the selected owner
                            children = OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, currentOwner);

                    }else if (parentOmeroObj.getType() == OmeroRawObjects.OmeroRawObjectType.ORPHANED_FOLDER) {
                        children = ((OmeroRawObjects.OrphanedFolder)parentOmeroObj).getImageList();
                    } else {
                        children = OmeroRawImageServerBrowserCommand.this.getChildren(parentOmeroObj, currentGroup, parentOmeroObj.getOwner());
                    }

                    // convert children into Java Tree items and filter them according to the filter
                    List<OmeroRawObjectTreeItem> items = filterList(children, comboGroup.getSelectionModel().getSelectedItem(),
                            comboOwner.getSelectionModel().getSelectedItem(), filterTemp)
                            .stream()
                            .map(OmeroRawObjectTreeItem::new)
                            .collect(Collectors.toList());

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

    /*
     *
     *
     *                                           Deprecated methods
     *
     *
     */

    /**
     * Build a colored-coded disk indicating the status of the server connection
     * @param loggedIn
     * @return
     * @deprecated use {@link OmeroRawBrowserTools#createStateNode(boolean)} instead
     */
    @Deprecated
    public static Node createStateNode(boolean loggedIn) {
        LogTools.warnOnce(logger, "createStateNode(boolean) is deprecated - " +
                "use OmeroRawBrowserTools.createStateNode(boolean) instead");
        return OmeroRawBrowserTools.createStateNode(loggedIn);
    }
}