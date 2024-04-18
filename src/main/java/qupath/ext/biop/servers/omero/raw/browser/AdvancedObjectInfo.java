package qupath.ext.biop.servers.omero.raw.browser;

import fr.igred.omero.annotations.AnnotationList;
import fr.igred.omero.exception.ServiceException;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import omero.gateway.exception.DSAccessException;
import org.controlsfx.glyphfont.GlyphFontRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.GridPaneUtils;
import qupath.lib.gui.QuPathGUI;

import java.util.Date;
import java.util.concurrent.ExecutionException;

/**
 * Class that handles the More info dialog to make appear all the annotations linked to the selected image / container
 *
 * @author Rémy Dornier
 *
 * Based on the initial work of
 * @author Melvin Gelbard
 */
class AdvancedObjectInfo {
    private static final String BOLD = "-fx-font-weight: bold";
    final private static Logger logger = LoggerFactory.getLogger(AdvancedObjectInfo.class);
    private final OmeroRawObjects.OmeroRawObject obj;
    private final OmeroRawAnnotations tags;
    private final OmeroRawAnnotations keyValuePairs;
    private final OmeroRawAnnotations attachments;
    private final OmeroRawAnnotations comments;
    private final OmeroRawAnnotations ratings;
//		private final OmeroRawAnnotations others;

    AdvancedObjectInfo(OmeroRawObjects.OmeroRawObject obj, OmeroRawClient client) {
        this.obj = obj;
        AnnotationList annotations;
        try {
            annotations = obj.getWrapper().getAnnotations(client.getSimpleClient());
        } catch (DSAccessException | ServiceException | ExecutionException e) {
            annotations = new AnnotationList();
        }

        this.tags = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotations.OmeroRawAnnotationType.TAG, annotations);
        this.keyValuePairs = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotations.OmeroRawAnnotationType.MAP, annotations);
        this.attachments = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotations.OmeroRawAnnotationType.ATTACHMENT, annotations);
        this.comments = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotations.OmeroRawAnnotationType.COMMENT, annotations);
        this.ratings = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotations.OmeroRawAnnotationType.RATING, annotations);
//			this.others = OmeroRawAnnotations.getOmeroAnnotations(OmeroRawAnnotationType.CUSTOM, annotations);

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
                if (window.getHeight() > screenBounds.getHeight()) {
                    window.setY(0);
                }
            }
        });

        // Resize Dialog when expanding/collapsing any TitledPane
        gp.getChildren().forEach(e -> {
            if (e instanceof TitledPane)
                ((TitledPane) e).heightProperty().addListener((v, o, n) -> dialog.getDialogPane().getScene().getWindow().sizeToScene());
        });

        // Catch escape key pressed
        dialog.getDialogPane().getScene().addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE)
                ((Stage) dialog.getDialogPane().getScene().getWindow()).close();
        });

        dialog.showAndWait();
    }

    /**
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
                for (var ann : anns) {
                    var ann2 = (OmeroRawAnnotations.TagAnnotation) ann;
                    tooltip = String.format("Owned by: %s", ann2.getOwner().getName());
                    //tooltip += String.format("\nAdded by: %s", ann2.addedBy().getName());
                    GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, tooltip, new Label(ann2.getValue()));
                }
                break;
            case MAP:
                for (var ann : anns) {
                    var ann2 = (OmeroRawAnnotations.MapAnnotation) ann;
                    for (var value : ann2.getValues().entrySet())
                        addKeyValueToGrid(gp, true, "Added by: " + ann2.getOwner().getName(), value.getKey(), value.getValue().isEmpty() ? "-" : value.getValue());
                }
                break;
            case ATTACHMENT:
                for (var ann : anns) {
                    var ann2 = (OmeroRawAnnotations.FileAnnotation) ann;
                    tooltip = String.format("Owned by: %s%sType: %s", ann2.getOwner().getName(), System.lineSeparator(), ann2.getMimeType());
                    //tooltip += String.format("\nAdded by: %s", ann2.addedBy().getName());
                    GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, tooltip, new Label(ann2.getFilename() + " (" + ann2.getFileSize() + " bytes)"));
                }
                break;
            case COMMENT:
                for (var ann : anns) {
                    var ann2 = (OmeroRawAnnotations.CommentAnnotation) ann;
                    GridPaneUtils.addGridRow(gp, gp.getRowCount(), 0, "Added by " + ann2.getOwner().getName(), new Label(ann2.getValue()));
                }
                break;
            case RATING:
                int rating = 0;
                for (var ann : anns) {
                    var ann2 = (OmeroRawAnnotations.LongAnnotation) ann;
                    rating += ann2.getValue();
                }

                for (int i = 0; i < Math.round(rating / anns.size()); i++)
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
            OmeroRawObjects.Image temp = (OmeroRawObjects.Image) obj;

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
     *
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
