package qupath.lib.images.servers.omero;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import qupath.lib.common.ThreadTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.ProjectCommands;
import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.gui.tools.GuiTools;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.gui.tools.PaneTools;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.omero.OmeroObjects.Dataset;
import qupath.lib.images.servers.omero.OmeroObjects.Group;
import qupath.lib.images.servers.omero.OmeroObjects.Image;
import qupath.lib.images.servers.omero.OmeroObjects.OmeroObject;
import qupath.lib.images.servers.omero.OmeroObjects.Owner;
import qupath.lib.images.servers.omero.OmeroObjects.Project;
import qupath.lib.images.servers.omero.OmeroObjects.Server;

class OmeroWebImageServerBrowserCommand implements Runnable {
	
	final private static Logger logger = LoggerFactory.getLogger(OmeroWebImageServerBrowserCommand.class);
	
	private QuPathGUI qupath;
	private OmeroWebImageServer server;
	private BorderPane mainPane;
	private BorderPane clientPane;
	private ComboBox<Owner> comboOwner = new ComboBox<>();
	private List<Owner> owners = new ArrayList<>();
	private List<Group> groups = new ArrayList<>();
	private TreeView<OmeroObject> tree;
	private TableView<Integer> description;
	private OmeroObject[] selectedObjects;
	private TextField filter = new TextField();
	private Canvas canvas;
	private int imgPrefSize = 256;
	private ProgressIndicator progressIndicator;
	private Button openBtn;
	
	private StringConverter<Owner> ownerStringConverter;
	
	private Map<String, BufferedImage> omeroIcons;
	// Get table item children in separate thread
	ExecutorService executorTable;
	
	private Map<Integer, BufferedImage> thumbnailBank = new HashMap<Integer, BufferedImage>();	// To store thumbnails
	
	private List<OmeroObject> serverChildrenList = new ArrayList<>();
	private Map<OmeroObject, List<OmeroObject>> projectMap = new ConcurrentHashMap<>();
	private Map<OmeroObject, List<OmeroObject>> datasetMap = new ConcurrentHashMap<>();
	
	private String[] projectAttributes;
	private String[] datasetAttributes;
	private String[] imageAttributes;
	
	private Integer[] imageIndices;
	private Integer[] datasetIndices;
	private Integer[] projectIndices;
	
	
    public OmeroWebImageServerBrowserCommand(QuPathGUI qupath) {
    	this.qupath = qupath;
    }
    
    @Override
    public void run() {
    	// Need to choose which server to browse first
    	if (qupath.getImageData() != null) {
    		var serverTemp = qupath.getImageData().getServer();
    		if (serverTemp instanceof OmeroWebImageServer)
    			server = (OmeroWebImageServer) serverTemp;
    	}
    	while (server == null) {
//    		String[] choices = qupath.getProject().getImageList().parallelStream().map(e -> e.getImageName()).toArray(String[]::new);
    		var entry = Dialogs.showChoiceDialog("No open OMERO image", "Choose an entry for a server to browse", qupath.getProject().getImageList(), null);
    		if (entry == null)
    			return;
			try {
				ImageServer<BufferedImage> serverTemp = entry.getServerBuilder().build();
				if (serverTemp instanceof OmeroWebImageServer)
	    			server = (OmeroWebImageServer)serverTemp;
	    		else
	    			Dialogs.showErrorMessage("Not an OMERO image", "Chosen image does not come from an OMERO server");
			} catch (Exception ex) {
				Dialogs.showErrorMessage("Error", "An error occurred while processing " + entry);
			}
    	}
    	
    	
    	
		mainPane = new BorderPane();
		selectedObjects = null;
		
		SplitPane browsePane = new SplitPane();
		GridPane browseLeftPane = new GridPane();
		GridPane browseRightPane = new GridPane();
		
		clientPane = new BorderPane();

		progressIndicator = new ProgressIndicator();
		progressIndicator.setPrefSize(20, 20);
		progressIndicator.setMinSize(20, 20);
		progressIndicator.setOpacity(0);
		
		executorTable = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("children-loader", true));
		
		// Get OMERO icons (project and dataset icons)
		omeroIcons = getOmeroIcons();
		
		// Create converter from Owner object to proper String
		ownerStringConverter = new StringConverter<Owner>() {
		    @Override
		    public String toString(Owner owner) {
		    	if (owner != null)
		    		return owner.getName();
		    	return null;
		    }

		    @Override
		    public Owner fromString(String string) {
		        return comboOwner.getItems().stream().filter(ap -> 
		            ap.getName().equals(string)).findFirst().orElse(null);
		    }
		};
		
		OmeroObjectTreeItem root = new OmeroObjectTreeItem(new OmeroObjects.Server(server));
		tree = new TreeView<>();
		tree.setRoot(root);
		tree.setShowRoot(false);
		tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
		tree.setCellFactory(n -> new OmeroObjectCell());
		tree.setOnMouseClicked(e -> {
	        if (e.getClickCount() == 2) {
	        	var selectedItem = tree.getSelectionModel().getSelectedItem();
	        	if (selectedItem != null && selectedItem.getValue() instanceof Image) {
	        		String typeFull = selectedItem.getValue().getType().toLowerCase();
	        		String type = typeFull.substring(typeFull.lastIndexOf('#') + 1);
	        		String url = server.getScheme() + "://" + server.getHost() + "/webclient/?show=" + type + "-" + selectedItem.getValue().getId();
	        		ProjectCommands.promptToImportImages(qupath, url);
	        	}
	        }
	    });
		
		owners.add(Owner.getAllMembersOwner());
		comboOwner.getItems().setAll(owners);
		comboOwner.getSelectionModel().selectFirst();
		comboOwner.setConverter(ownerStringConverter);
		comboOwner.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> refreshTree());
		
		description = new TableView<>();
		TableColumn<Integer, String> attributeCol = new TableColumn<>("Attribute");
		TableColumn<Integer, String> valueCol = new TableColumn<>("Value");
		valueCol.setPrefWidth(150.0);
		
		projectAttributes = new String[] {"Name", 
				"Id",
				"Description",
				"Owner",
				"Group",
				"Num. datasets"};
		
		datasetAttributes = new String[] {"Name", 
				"Id", 
				"Description",
				"Owner",
				"Group",
				"Num. images"};
		
		imageAttributes = new String[] {"Name", 
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

		attributeCol.setCellValueFactory(cellData -> {
			var selectedItems = tree.getSelectionModel().getSelectedItems();
			if (selectedItems.size() == 1 && selectedItems.get(0).getValue() != null) {
				var type = selectedItems.get(0).getValue().getType().toLowerCase();
				if (type.endsWith("#project"))
					return new ReadOnlyObjectWrapper<String>(projectAttributes[cellData.getValue()]);
				else if (type.endsWith("#dataset"))
					return new ReadOnlyObjectWrapper<String>(datasetAttributes[cellData.getValue()]);
				else if (type.endsWith("#image"))
					return new ReadOnlyObjectWrapper<String>(imageAttributes[cellData.getValue()]);				
			}
			return new ReadOnlyObjectWrapper<String>("");
			
		});
		valueCol.setCellValueFactory(cellData -> {
			if (selectedObjects != null && selectedObjects.length == 1) 
				return getObjectInfo(cellData.getValue(), selectedObjects[0]);
			else
				return new ReadOnlyObjectWrapper<String>();
		});
		valueCol.setCellFactory(n -> new TableCell<Integer, String>() {
			@Override
	        protected void updateItem(String item, boolean empty) {
				super.updateItem(item, empty);
				setText(item);
				setTooltip(new Tooltip(item));
			}
		});
		
		// Get thumbnails in separate thread
		ExecutorService executor = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("thumbnail-loader", true));

		tree.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
			clearCanvas();
			if (n != null) {
				if (description.getPlaceholder() == null)
					description.setPlaceholder(new Label("Multiple elements selected"));
				selectedObjects = tree.getSelectionModel().getSelectedItems().stream()
						.map(item -> item.getValue())
						.toArray(OmeroObject[]::new);

				updateDescription();
				if (selectedObjects.length == 1) {
					var selectedObjectLocal = n.getValue();
					if (selectedObjects.length == 1 && selectedObjects[0] instanceof Image) {
						// Check if thumbnail was previously cached
						if (thumbnailBank.containsKey(selectedObjectLocal.getId()))
							setThumbnail(thumbnailBank.get(selectedObjectLocal.getId()));
						else {
							// Get thumbnail from JSON API in separate thread (and show progress indicator)
							progressIndicator.setOpacity(100);
							executor.submit(() -> {
								// TODO: check if synchronized block here is really necessary
								synchronized(thumbnailBank) {
									try {
										BufferedImage img = OmeroTools.getThumbnail(server, selectedObjectLocal.getId(), imgPrefSize);
										thumbnailBank.put(selectedObjectLocal.getId(), img);
										setThumbnail(img);
										Platform.runLater(() -> {
											progressIndicator.setOpacity(0);
										});		
									} catch (IOException e) {
										logger.warn("Error loading thumbnail: " + e.getLocalizedMessage());
									}
								}
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
				// Change text on "Open .." button
				Platform.runLater(() -> {
					if (selectedObjects.length > 1)
						openBtn.setText("Open selected");
					else if (selectedObjects[0] instanceof Image)
						openBtn.setText("Open image");
					else if (selectedObjects[0] instanceof Dataset)
						openBtn.setText("Open dataset");
					else if (selectedObjects[0] instanceof Project)
						openBtn.setText("Open Omero project");
					
					var allSupported = !List.of(selectedObjects).stream().allMatch(obj -> isSupported(obj));
					openBtn.setDisable(allSupported);
				});
			}
		});
		
		Button expandBtn = new Button("Expand all");
		Button collapseBtn = new Button("Collapse all");

		expandBtn.setOnMouseClicked(e -> {
			tree.getSelectionModel().clearSelection();
			expandTreeView(tree.getRoot());
		});
		collapseBtn.setOnMouseClicked(e -> collapseTreeView(tree.getRoot()));
		GridPane expansionPane = new GridPane();
		PaneTools.addGridRow(expansionPane, 0, 0, "Expand/collapse items", expandBtn, collapseBtn);
		
		filter.setPromptText("Search project");
		filter.textProperty().addListener((v, o, n) -> {
			refreshTree();
			if (n.isEmpty())
				collapseTreeView(tree.getRoot());
			else
				expandTreeView(tree.getRoot());
				
		});
		
		GridPane searchPane = new GridPane();
		Button advancedSearchBtn = new Button("Advanced search");
		advancedSearchBtn.setOnAction(e -> new AdvancedSearch(server));
		searchPane.addRow(0,  filter, advancedSearchBtn);
		
		PaneTools.addGridRow(browseLeftPane, 0, 0, "Filter by owner", comboOwner);
		PaneTools.addGridRow(browseLeftPane, 1, 0, null, tree);
		PaneTools.addGridRow(browseLeftPane, 2, 0, null, expansionPane);
		PaneTools.addGridRow(browseLeftPane, 3, 0, null, searchPane);
		
		canvas = new Canvas();
		description.getColumns().addAll(attributeCol, valueCol);
		
		Button clipboardBtn = new Button("Copy URI to clipboard");
		clipboardBtn.disableProperty().bind(tree.getSelectionModel().selectedItemProperty().isNull());
		openBtn = new Button("Open image");
		openBtn.setDisable(true);
		
		GridPane actionBtnPane = new GridPane();
		PaneTools.addGridRow(actionBtnPane, 0, 0, null, clipboardBtn, openBtn);

		
		clipboardBtn.setOnMouseClicked(e -> {
			var selected = tree.getSelectionModel().getSelectedItems();
			if (selected != null) {
				try {
					ClipboardContent content = new ClipboardContent();
					if (selected.size() == 1) {
						content.putString(getURI(selected.get(0).getValue()));						
					} else {
						List<String> URIs = new ArrayList<>();
						for (var item: selected) {
							URIs.add(getURI(item.getValue()));						
						}
						content.putString("[" + String.join(", ", URIs) + "]");						
					}
					Clipboard.getSystemClipboard().setContent(content);
				} catch (URISyntaxException ex) {
					logger.error("Could not copy to clipboard:", ex.getLocalizedMessage());
				}
			}
		});
		
		openBtn.setOnMouseClicked(e -> {
			String[] URIs = tree.getSelectionModel().getSelectedItems().stream()
					.flatMap(item -> {
						try {
							return OmeroTools.getURIs(URI.create(getURI(item.getValue()))).stream();
						} catch (URISyntaxException | IOException ex) {
							logger.error("Could not open " + item.getValue().getName(), ex.getLocalizedMessage());
						}
						return null;
					}).map(item -> item.toString())
					  .toArray(String[]::new);
			ProjectCommands.promptToImportImages(qupath, URIs);
		});
		
		
		PaneTools.addGridRow(browseRightPane, 0, 0, null, canvas);
		PaneTools.addGridRow(browseRightPane, 1, 0, null, description);
		PaneTools.addGridRow(browseRightPane, 2, 0, null, actionBtnPane);
		

        // Anchor the tabs and progressIndicator
        AnchorPane anchor = new AnchorPane();
        anchor.getChildren().addAll(browsePane, progressIndicator);
        AnchorPane.setTopAnchor(progressIndicator, 3.0);
        AnchorPane.setRightAnchor(progressIndicator, 5.0);
        AnchorPane.setTopAnchor(browsePane, 1.0);
        AnchorPane.setRightAnchor(browsePane, 1.0);
        AnchorPane.setLeftAnchor(browsePane, 1.0);
        AnchorPane.setBottomAnchor(browsePane, 1.0);
        
        // Set HGrow and VGrow
		GridPane.setHgrow(description,  Priority.ALWAYS);
		GridPane.setHgrow(tree, Priority.ALWAYS);
		GridPane.setHgrow(expandBtn, Priority.ALWAYS);
		GridPane.setHgrow(collapseBtn, Priority.ALWAYS);
		GridPane.setHgrow(filter, Priority.ALWAYS);
		GridPane.setHgrow(advancedSearchBtn, Priority.ALWAYS);
		GridPane.setHgrow(clipboardBtn, Priority.ALWAYS);
		GridPane.setHgrow(openBtn, Priority.ALWAYS);
		GridPane.setVgrow(description, Priority.ALWAYS);
		GridPane.setVgrow(tree, Priority.ALWAYS);
		
		// Set max width & height
		comboOwner.setMaxWidth(Double.MAX_VALUE);
		expandBtn.setMaxWidth(Double.MAX_VALUE);
		collapseBtn.setMaxWidth(Double.MAX_VALUE);
		expansionPane.setMaxWidth(Double.MAX_VALUE);
        advancedSearchBtn.setMaxWidth(Double.MAX_VALUE);
        browseLeftPane.setMaxWidth(Double.MAX_VALUE);
        clipboardBtn.setMaxWidth(Double.MAX_VALUE);
        openBtn.setMaxWidth(Double.MAX_VALUE);
		description.setMaxHeight(Double.MAX_VALUE);
		
		mainPane.setCenter(anchor);
		browsePane.getItems().addAll(browseLeftPane, browseRightPane);
		
		
		Stage dialog = new Stage();
		dialog.sizeToScene();
		QuPathGUI qupath = QuPathGUI.getInstance();
		if (qupath != null)
			dialog.initOwner(QuPathGUI.getInstance().getStage());
		dialog.setTitle("OMERO web server");
		dialog.setScene(new Scene(mainPane));
		dialog.setOnCloseRequest(e -> shutdownPool());
		dialog.showAndWait();
    }

    
    private Map<String, BufferedImage> getOmeroIcons() {
    	Map<String, BufferedImage> map = new HashMap<>();
		try {
			// Load project icon
			URL url = new URL(server.getScheme() + "://" + server.getHost() + "/static/webgateway/img/folder16.png");
			map.put("project", ImageIO.read(url));
			
			// Load dataset icon
			URL url2 = new URL(server.getScheme() + "://" + server.getHost() + "/static/webgateway/img/folder_image16.png");
			map.put("dataset", ImageIO.read(url2));
		} catch (IOException e) {
			logger.warn("Could not load OMERO icons.", e.getLocalizedMessage());
		}
		return map;
	}


	private String getURI(OmeroObject omeroObj) throws URISyntaxException {
		var apiUrl = new URI(omeroObj.getAPIURLString());
		var type = "project";
		if (omeroObj instanceof Dataset)
			type = "dataset";
		else if (omeroObj instanceof Image)
			type = "image";
		
		return apiUrl.getScheme() + "://" + apiUrl.getHost() + "/webclient/?show=" + type + "-" + omeroObj.getId();
	}


	private void refreshTree() {
		tree.setRoot(null);
		tree.refresh();
		tree.setRoot(new OmeroObjectTreeItem(new OmeroObjects.Server(server)));
		tree.refresh();
	}


	private ObservableValue<String> getObjectInfo(Integer index, OmeroObject omeroObject) {
		if (omeroObject == null)
			return new ReadOnlyObjectWrapper<String>();
		String[] outString = null;
		String name = omeroObject.getName();
		String id = omeroObject.getId() + "";
		String owner = omeroObject.getOwner().getName();
		String group = omeroObject.getGroup().getName();
		if (omeroObject.getType().toLowerCase().endsWith("#project")) {
			String description = ((Project)omeroObject).getDescription();
			if (description == null || description.isEmpty())
				description = "-";
			String nChildren = omeroObject.getNChildren() + "";
			outString = new String[] {name, id, description, owner, group, nChildren};
			
		} else if (omeroObject.getType().toLowerCase().endsWith("#dataset")) {
			String description = ((Dataset)omeroObject).getDescription();
			if (description == null || description.isEmpty())
				description = "-";
			String nChildren = omeroObject.getNChildren() + "";
			outString = new String[] {name, id, description, owner, group, nChildren};

		} else if (omeroObject.getType().toLowerCase().endsWith("#image")) {
			Image obj = (Image)omeroObject;
			String acquisitionDate = obj.getAcquisitionDate() == -1 ? "-" : new Date(obj.getAcquisitionDate()*1000).toString();
			String width = obj.getImageDimensions()[0] + " px";
			String height = obj.getImageDimensions()[1] + " px";
			String c = obj.getImageDimensions()[2] + "";
			String z = obj.getImageDimensions()[3] + "";
			String t = obj.getImageDimensions()[4] + "";
			String pixelSizeX = obj.getPhysicalSizes()[0] == null ? "-" : obj.getPhysicalSizes()[0].getValue() + " " + obj.getPhysicalSizes()[0].getSymbol();
			String pixelSizeY = obj.getPhysicalSizes()[1] == null ? "-" : obj.getPhysicalSizes()[1].getValue() + " " + obj.getPhysicalSizes()[1].getSymbol();
			String pixelSizeZ = obj.getPhysicalSizes()[2] == null ? "-" : obj.getPhysicalSizes()[2].getValue() + obj.getPhysicalSizes()[2].getSymbol();
			String pixelType = obj.getPixelType();
			outString = new String[] {name, id, owner, group, acquisitionDate, width, height, c, z, t, pixelSizeX, pixelSizeY, pixelSizeZ, pixelType};
		}
		
		return new ReadOnlyObjectWrapper<String>(outString[index]);
	}


	private void updateDescription() {
		ObservableList<Integer> indexList = FXCollections.observableArrayList();
		if (selectedObjects.length == 1) {
			if (selectedObjects[0].getType().toLowerCase().endsWith("#project")) {
				projectIndices = new Integer[projectAttributes.length];
				for (int index = 0; index < projectAttributes.length; index++) projectIndices[index] = index;
				indexList = FXCollections.observableArrayList(projectIndices);
				
			} else if (selectedObjects[0].getType().toLowerCase().endsWith("#dataset")) {
				datasetIndices = new Integer[datasetAttributes.length];
				for (int index = 0; index < datasetAttributes.length; index++) datasetIndices[index] = index;
				indexList = FXCollections.observableArrayList(datasetIndices);
				
			} else if (selectedObjects[0].getType().toLowerCase().endsWith("#image")) {
				imageIndices = new Integer[imageAttributes.length];
				for (int index = 0; index < imageAttributes.length; index++) imageIndices[index] = index;
				indexList = FXCollections.observableArrayList(imageIndices);
			}
		}
		description.getItems().setAll(indexList);
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
		
		var wi =  SwingFXUtils.toFXImage(img, null);
		if (wi == null)
			return wi;
		else
			GuiTools.paintImage(canvas, wi);
		
		return wi;
	}



	/**
	 * Display an OMERO object using its name.
	 */
	private class OmeroObjectCell extends TreeCell<OmeroObject> {
		
		Canvas canvas = new Canvas();
		
		@Override
        public void updateItem(OmeroObject item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
            	String name;
            	setOpacity(1.0);
            	if (item.getType().toLowerCase().endsWith("server"))
            		name = server.getHost();
            	else if (item.getType().toLowerCase().endsWith("image")) {
            		name = item.getName();
            		setGraphic(null);
            		if (!isSupported(item))
            			setOpacity(0.5);
            	} else {
            		// If it's either project or dataset, need to set graphic with icon
            		BufferedImage img = null;
            		if (item.getType().toLowerCase().endsWith("project")) {
            			name = item.getName() + " (" + item.getNChildren() + ")";
            			img = omeroIcons.get("project");
            			
            		} else {
            			name = item.getName() + " (" + item.getNChildren() + ")";
            			img = omeroIcons.get("dataset");
            		}
            		
            		if (img != null) {
            			paintBufferedImageOnCanvas(img, canvas, 15);
            			setGraphic(canvas);
            		}
            	}
                setText(name);
            }
        }		
	}
	
	/**
	 * TreeItem to help with the display of Omero objects.
	 */
	private class OmeroObjectTreeItem extends TreeItem<OmeroObject> {
		
		private boolean computed = false;
		
		public OmeroObjectTreeItem(OmeroObject obj) {
			super(obj);
		}

		/**
		 * This method gets the children of the current tree item.
		 * Only the currently expanded items will call this method.
		 * <p>
		 * If we have never seen the current tree item, a JSON request
		 * will be sent to the OMERO API to get its children, this value 
		 * will then be stored (cache). If we have seen this tree item 
		 * before, it will simply return the stored value.
		 * 
		 * All stored values are in {@code projectMap} & {@code datasetMap}.
		 */
		@Override
		public ObservableList<TreeItem<OmeroObject>> getChildren() {
			if (!isLeaf() && !computed) {
				progressIndicator.setOpacity(100);
				var filterTemp = filter.getText();
				executorTable.submit(() -> {
					var omeroObj = this.getValue();
					
					// Get children and populate maps if necessary
					List<OmeroObject> children = getChildren(omeroObj);
					
					// If server, update list of owners (and combo box)
					if (omeroObj instanceof Server) {
						var tempOwners = children.stream()
								.map(e -> e.getOwner())
								.filter(distinctByName(Owner::getName))
								.collect(Collectors.toList());
						var tempGroups = children.stream()
								.map(e -> e.getGroup())
								.filter(distinctByName(Group::getName))
								.collect(Collectors.toList());
						if (tempOwners.size() > owners.size()) {
							owners.clear();
							owners.add(Owner.getAllMembersOwner());
							owners.addAll(tempOwners);
							Platform.runLater(() -> {
								comboOwner.getItems().setAll(owners);
							});								
						}
						if (tempGroups.size() > groups.size()) {
							groups.clear();
							groups.add(Group.getAllGroupsObject());
							groups.addAll(tempGroups);
						}
					}
					
					var items = children.parallelStream()
							.filter(e -> {
								var selected = comboOwner.getSelectionModel().getSelectedItem();
								if (selected != null) {
									if (selected.getName().equals("All members "))
										return true;
									return e.getOwner().getName().equals(comboOwner.getSelectionModel().getSelectedItem().getName());
								}
								return true;
							})
							.filter(e -> matchesSearch(e, filterTemp))
							.map(e -> new OmeroObjectTreeItem(e))
							.collect(Collectors.toList());
					
					super.getChildren().setAll(items);
					computed = true;

					Platform.runLater(() -> progressIndicator.setOpacity(0));
					return super.getChildren();
				});
			}
			//progressIndicator.setOpacity(0);
			return super.getChildren();
		}
		
		
		@Override
		public boolean isLeaf() {
			var obj = this.getValue();
			if (obj.getType().toLowerCase().endsWith("#server"))
				return false;
			if (obj.getType().toLowerCase().endsWith("#image"))
				return true;
			return obj.getNChildren() == 0;
		}
		
		private boolean matchesSearch(OmeroObject obj, String filter) {
			if (filter == null || filter.isEmpty())
				return true;
			
			if (obj instanceof Server)
				return true;
			
			if (obj.getParent() instanceof Server)
				return obj.getName().toLowerCase().contains(filter.toLowerCase());
			
			return matchesSearch(obj.getParent(), filter);
		}
		
		/**
		 * Return a list of all children of the specified omeroObj, either by requesting them 
		 * to the server or by retrieving the stored value from the maps. If a request was 
		 * necessary, the value will be stored in the map to avoid future unnecessary computation.
		 * <p>
		 * No filter is applied to the object's children.
		 * 
		 * @param omeroObj
		 * @return list of omeroObj's children
		 */
		private List<OmeroObject> getChildren(OmeroObject omeroObj) {
			// Check if we already have the children for this OmeroObject (avoid sending request)
			if (omeroObj instanceof Server && serverChildrenList.size() > 0)
				return serverChildrenList;
			else if (omeroObj instanceof Project && projectMap.containsKey((Project)omeroObj))
				return projectMap.get((Project)omeroObj);
			else if (omeroObj instanceof Dataset && datasetMap.containsKey((Dataset)omeroObj))
				return datasetMap.get((Dataset)omeroObj);
			else if (omeroObj instanceof Image)
				return new ArrayList<OmeroObject>();
			
			List<OmeroObject> children;
			try {
				children = OmeroTools.getOmeroObjects(server, omeroObj);
				
				if (omeroObj instanceof Server)
					serverChildrenList = children;
				else if (omeroObj instanceof Project)
					projectMap.put(omeroObj, children);
				else if (omeroObj instanceof Dataset)
					datasetMap.put(omeroObj, children);
			} catch (IOException e) {
				logger.error("Couldn't fetch server information", e.getLocalizedMessage());
				return new ArrayList<OmeroObject>();
			}
			return children;
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
	
	void setThumbnail(BufferedImage img) {
		GraphicsContext gc = canvas.getGraphicsContext2D();
		gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

		var wi =  SwingFXUtils.toFXImage(img, null);
		if (wi == null)
			return;
		else
			GuiTools.paintImage(canvas, wi);
		
		canvas.setStyle("-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 4, 0, 1, 1);");
		canvas.setWidth(imgPrefSize);
		canvas.setHeight(imgPrefSize);
	}
	
	/**
	 * Return whether the image type is supported by QuPath
	 * @param omeroObj
	 * @return isSupported
	 */
	private boolean isSupported(OmeroObject omeroObj) {
		if (omeroObj == null || !omeroObj.getType().toLowerCase().endsWith("image"))
			return true;
		return ((Image)omeroObj).getPixelType().equals("uint8");
		
	}
	
	private void clearCanvas() {
		GraphicsContext gc = canvas.getGraphicsContext2D();
		gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
	}
	
	/**
	 * Set the specified item and its children to the specified expanded mode
	 * @param item
	 * @param expanded
	 */
	private void expandTreeView(TreeItem<OmeroObject> item){
	    if (item != null && !item.isLeaf()) {
	    	if (!(item.getValue() instanceof Server))
	    		item.setExpanded(true);
	    	
    		for (var child: item.getChildren()) {
    			expandTreeView(child);
	        }
	    }
	}
	
	private void collapseTreeView(TreeItem<OmeroObject> item){
	    if (item != null && !item.isLeaf()) {
	    	if (item.getValue() instanceof Server) {
	    		for (var child: item.getChildren()) {
	    			child.setExpanded(false);
	    		}
	    	}
	    }
	}

	private class AdvancedSearch {
		
		TableView<SearchResult> resultsTableView = new TableView<>();
		ObservableList<SearchResult> obsResults = FXCollections.observableArrayList();
		
		TextField searchTf;
		CheckBox restrictedByName;
		CheckBox restrictedByDesc;
		CheckBox searchForImages;
		CheckBox searchForDatasets;
		CheckBox searchForProjects;
		CheckBox searchForWells;
		CheckBox searchForPlates;
		CheckBox searchForScreens;
		ComboBox<Owner> ownedByCombo;
		ComboBox<Group> groupCombo;
		
		int prefScale = 50;
		
		Button searchBtn;
		ProgressIndicator progressIndicator2;
		
		// Search query in separate thread
		ExecutorService executor = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("query-processing", true));
		
		// Load thumbnail in separate thread
		ExecutorService executor2;
		
		private final Pattern patternRow = Pattern.compile("<tr id=\"(.+?)-(.+?)\".+?</tr>", Pattern.DOTALL | Pattern.MULTILINE);
	    private final Pattern patternDesc = Pattern.compile("<td class=\"desc\"><a>(.+?)</a></td>");
	    private final Pattern patternDate = Pattern.compile("<td class=\"date\">(.+?)</td>");
	    private final Pattern patternGroup = Pattern.compile("<td class=\"group\">(.+?)</td>");
	    private final Pattern patternLink = Pattern.compile("<td><a href=\"(.+?)\"");

	    Pattern[] patterns = new Pattern[] {patternDesc, patternDate, patternDate, patternGroup, patternLink};
		
		private AdvancedSearch(OmeroWebImageServer server) {
			
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
			PaneTools.addGridRow(comboPane, 0, 0, "Data owned by", new Label("Owned by:"),  ownedByCombo);
			PaneTools.addGridRow(comboPane, 1, 0, "Data from group", new Label("Group:"), groupCombo);
			
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
			progressIndicator2.setPrefSize(20, 20);
			progressIndicator2.setMinSize(20, 20);
			searchBtn.setOnAction(e -> {
				searchBtn.setGraphic(progressIndicator2);
				// Show progress indicator (loading)
				Platform.runLater(() -> {
					// TODO: next line doesn't work
					searchBtn.setGraphic(progressIndicator2);
					searchBtn.setText(null);
				});
				
				// Process the query in different thread
				executor.submit(() -> searchQuery());
				
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
			
			Button openBtn = new Button("Open image");
			openBtn.disableProperty().bind(resultsTableView.getSelectionModel().selectedItemProperty().isNull());
			openBtn.setOnAction(e -> {
				String[] URIs = resultsTableView.getSelectionModel().getSelectedItems().stream()
						.flatMap(item -> {
							try {
								return OmeroTools.getURIs(item.link.toURI()).stream();
							} catch (URISyntaxException | IOException ex) {
								logger.error("Error while opening " + item.name, ex.getLocalizedMessage());
							}
							return null;
						}).map(item -> item.toString())
						  .toArray(String[]::new);
				if (URIs.length > 0)
					ProjectCommands.promptToImportImages(qupath, URIs);
				else
					Dialogs.showErrorMessage("No image found", "No image found in OMERO object.");
			});
			openBtn.setMaxWidth(Double.MAX_VALUE);
			
			resultsTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
			
			
			int row = 0;
			PaneTools.addGridRow(searchOptionPane, row++, 0, "The query to search", queryPane);
			PaneTools.addGridRow(searchOptionPane, row++, 0, null, new Separator());
			PaneTools.addGridRow(searchOptionPane, row++, 0, "Restrict by", new Label("Restrict by:"));
			PaneTools.addGridRow(searchOptionPane, row++, 0, "Restrict by", restrictByPane);
			PaneTools.addGridRow(searchOptionPane, row++, 0, null, new Separator());
			PaneTools.addGridRow(searchOptionPane, row++, 0, "Search for", new Label("Search for:"));
			PaneTools.addGridRow(searchOptionPane, row++, 0, "Search for", searchForPane);
			PaneTools.addGridRow(searchOptionPane, row++, 0, null, new Separator());
			PaneTools.addGridRow(searchOptionPane, row++, 0, null, comboPane);
			PaneTools.addGridRow(searchOptionPane, row++, 0, null, buttonPane);
			PaneTools.addGridRow(searchOptionPane, row++, 0, "Open selected image", openBtn);
			
			
			TableColumn<SearchResult, SearchResult> typeCol = new TableColumn<>("Type");
		    TableColumn<SearchResult, String> nameCol = new TableColumn<>("Name");
		    TableColumn<SearchResult, String> acquisitionCol = new TableColumn<>("Acquired");
		    TableColumn<SearchResult, String> importedCol = new TableColumn<>("Imported");
		    TableColumn<SearchResult, String> groupCol = new TableColumn<>("Group");
		    TableColumn<SearchResult, SearchResult> linkCol = new TableColumn<>("Link");
		    
		    typeCol.setCellValueFactory(n -> new ReadOnlyObjectWrapper<>(n.getValue()));
		    typeCol.setCellFactory(n -> new TableCell<SearchResult, SearchResult>() {
		    	
				@Override
		        protected void updateItem(SearchResult item, boolean empty) {
		            super.updateItem(item, empty);
		            BufferedImage img = null;
		            Canvas canvas = new Canvas();
		            
		            if (item == null || empty) {
						setTooltip(null);
						setText(null);
						return;
					}
		            
		            if (item.type.toLowerCase().equals("project"))
		            	img = omeroIcons.get("project");
		            else if (item.type.toLowerCase().equals("dataset"))
		            	img = omeroIcons.get("dataset");
		            else {
		            	// To avoid ConcurrentModificationExceptions
						var it = thumbnailBank.keySet().iterator();
						while (it.hasNext()) {
							var id = it.next();
							if (id == item.id) {
								img = thumbnailBank.get(id);
								continue;
							}
						}
					}
					
		            if (img != null ) {
		            	var wi = paintBufferedImageOnCanvas(img, canvas, prefScale);
		            	// Setting tooltips on hover
		            	Tooltip tooltip = new Tooltip();
		            	ImageView imageView = new ImageView(wi);
		            	imageView.setFitHeight(250);
		            	imageView.setPreserveRatio(true);
		            	tooltip.setGraphic(imageView);
		            	setGraphic(canvas);
		            	setText(null);
		            }
					
					setTooltip(new Tooltip(item.name));
					setAlignment(Pos.CENTER);
				}
		    });
		    nameCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().name));
		    acquisitionCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().acquired.toString()));
		    importedCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().imported.toString()));
		    groupCol.setCellValueFactory(n -> new ReadOnlyStringWrapper(n.getValue().group));
		    linkCol.setCellValueFactory(n -> new ReadOnlyObjectWrapper<>(n.getValue()));
		    linkCol.setCellFactory(n -> new TableCell<SearchResult, SearchResult>() {
		        private final Button button = new Button("Link");

		        @Override
		        protected void updateItem(SearchResult item, boolean empty) {
		            super.updateItem(item, empty);
		            if (item == null) {
		                setGraphic(null);
		                return;
		            }

		            button.setOnAction(e -> QuPathGUI.launchBrowserWindow(item.link.toString()));
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
							List<URI> URIs = OmeroTools.getURIs(selectedItem.link.toURI());
							var uriStrings = URIs.parallelStream().map(uri -> uri.toString()).toArray(String[]::new);
							if (URIs.size() > 0)
								ProjectCommands.promptToImportImages(qupath, uriStrings);
							else
								Dialogs.showErrorMessage("No image found", "No image found in OMERO object.");
						} catch (IOException | URISyntaxException ex) {
							logger.error("Error while opening " + selectedItem.name, ex.getLocalizedMessage());
						}
		        	}
		        }
		    });
			
			resultsTableView.getSelectionModel().selectedItemProperty().addListener((v, o, n) -> {
				if (n == null)
					return;
				
				if (resultsTableView.getSelectionModel().getSelectedItems().size() == 1)
					openBtn.setText("Open " + n.type);
				else
					openBtn.setText("Open OMERO objects");
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
				executor.shutdownNow();
				executor2.shutdownNow();
			});
			dialog.showAndWait();
		}
		
		
		private void searchQuery() {
			List<SearchResult> results = new ArrayList<>();
			
			String fields = "";
			fields += restrictedByName.isSelected() ? "&field=name" : "";
			fields += restrictedByDesc.isSelected() ? "&field=description" : "";
			
			String datatypes = "";
			datatypes += searchForImages.isSelected() ? "&datatype=images" : "";
			datatypes += searchForDatasets.isSelected() ? "&datatype=datasets" : "";
			datatypes += searchForProjects.isSelected() ? "&datatype=projects" : "";
			datatypes += searchForWells.isSelected() ? "&datatype=wells" : "";
			datatypes += searchForPlates.isSelected() ? "&datatype=plates" : "";
			datatypes += searchForScreens.isSelected() ? "&datatype=screens" : "";
			
			int owner = ownedByCombo.getSelectionModel().getSelectedItem().getId();
			int group = groupCombo.getSelectionModel().getSelectedItem().getId();

			URL url;
			try {
				url = new URL(server.getScheme() + "://" +
						server.getHost() + 
						"/webclient/load_searching/form/?query=" + searchTf.getText() +
						String.join("&", fields) +
						datatypes +
						"&searchGroup=" + group +
						"&ownedBy=" + owner +
						"&useAcquisitionDate=false" +
						"&startdateinput=&enddateinput=&_=1599819408072");
				
			} catch (MalformedURLException ex) {
				logger.error(ex.getLocalizedMessage());
				Dialogs.showErrorMessage("Search query", "An error occurred. Check log in for more information.");
				return;
			}
			
			InputStreamReader reader;
			try {
				reader = new InputStreamReader(url.openStream());
				String response = "";
				var temp = reader.read();
				while (temp != -1) {
					response += (char)temp;
					temp = reader.read();
				}
				
				if (!response.contains("No results found"))
					results = parseHTML(response);
				
				populateThumbnailBank(results);
				updateTableView(results);
				
			} catch (IOException e) {
				logger.error(e.getLocalizedMessage());
				Dialogs.showErrorMessage("Search query", "An error occurred. Check log in for more information.");
				return;
			}
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
					logger.error("Could not parse search result.", e.getLocalizedMessage());
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
						for (var id: thumbnailBank.keySet()) {
						     if (id == e.id)
						    	 return false;
						}
						return true;
					})
					.collect(Collectors.toList());
			
			if (thumbnailsToQuery.isEmpty())
				return;
			
			if (executor2 != null)
				executor2.shutdownNow();
			executor2 = Executors.newSingleThreadExecutor(ThreadTools.createThreadFactory("batch-thumbnail-request", true));
			 
			for (var searchResult: thumbnailsToQuery) {
				executor2.submit(() -> {
					BufferedImage thumbnail;
					try {
						thumbnail = OmeroTools.getThumbnail(server, searchResult.id, imgPrefSize);
						synchronized (thumbnailBank) {
							thumbnailBank.put(searchResult.id, thumbnail);
						}
						Platform.runLater(() -> resultsTableView.refresh());
					} catch (IOException e) {
						logger.warn("Could not load thumbnail.", e.getLocalizedMessage());
					}
				});
			}
		}
	}


	private class SearchResult {
		String type;
		int id;
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		String name;
		Date acquired;
		Date imported;
		String group;
		URL link;
		
		public SearchResult(String[] values) throws ParseException, MalformedURLException {
			this.type = values[0];
			this.id = Integer.parseInt(values[1]);
			this.name = values[2];
			this.acquired = dateFormat.parse(values[3]);
			this.imported = dateFormat.parse(values[4]);
			this.group = values[5];
			this.link = URI.create(server.getScheme() + "://" + server.getHost() + values[6]).toURL();
		}
	}
	
	
	public void shutdownPool() {
		executorTable.shutdownNow();
	}
}