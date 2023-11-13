package qupath.ext.biop.servers.omero.raw;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.apache.commons.lang3.StringUtils;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.Map;


/**
 * Command to import QuPath metatdata to OMERO server where the
 * current image is hosted. Metadata are added as a new key-value pair.
 *
 * @author Rémy Dornier (parts of the code are taken from {@link OmeroRawWriteAnnotationObjectsCommand}.
 *
 */
public class OmeroRawWriteMetadataCommand  implements Runnable{

    private final String title = "Sending metadata";
    private final QuPathGUI qupath;
    public OmeroRawWriteMetadataCommand(QuPathGUI qupath)  {
        this.qupath = qupath;
    }

    @Override
    public void run() {

        // get the current image
        ImageServer<BufferedImage> imageServer = this.qupath.getViewer().getServer();

        // Check if OMERO server
        if (!(imageServer instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for import options
        GridPane pane = new GridPane();
        final ToggleGroup group = new ToggleGroup();

        RadioButton rbKeepMetadata = new RadioButton("Keep existing and add new");
        rbKeepMetadata.setToggleGroup(group);

        RadioButton rbReplaceMetadata = new RadioButton("Replace existing and add new");
        rbReplaceMetadata.setToggleGroup(group);
        rbReplaceMetadata.setSelected(true);

        RadioButton rbDeleteMetadata = new RadioButton("Delete all and add new");
        rbDeleteMetadata.setToggleGroup(group);

        int row = 0;
        pane.add(new Label("Select sending options"), 0, row++, 2, 1);
        pane.add(rbKeepMetadata, 0, row++);
        pane.add(rbReplaceMetadata, 0, row++);
        pane.add(rbDeleteMetadata, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean replaceMetadata = rbReplaceMetadata.isSelected();
        boolean deleteMetadata = rbDeleteMetadata.isSelected();
        boolean keepMetadata = rbKeepMetadata.isSelected();

        // get keys
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());

        // build a map of key and values from metadata
        Map<String,String> keyValues = entry.getMetadataMap();

        if (keyValues.keySet().size() > 0) {
            // Ask user if he/she wants to send all annotations
            boolean confirm = Dialogs.showConfirmDialog(title, String.format("Do you want to send all metadata as key-values or tags ? (%d %s)",
                    keyValues.keySet().size(),
                    (keyValues.keySet().size() == 1 ? "object" : "objects")));

            if (!confirm)
                return;
        }else{
            Dialogs.showWarningNotification(title, "The current image does not contain any metadata");
            return;
        }

        String objectString = "KVP" + (keyValues.keySet().size() == 1 ? "" : "s") + "/tag" + (keyValues.keySet().size() == 1 ? "" : "s");
        boolean wasSaved = true;

        // send metadata to OMERO
        if(deleteMetadata)
            wasSaved = OmeroRawScripting.sendMetadataOnOmeroAndDeleteKeyValues(keyValues,(OmeroRawImageServer)imageServer);
        if(keepMetadata)
            wasSaved = OmeroRawScripting.sendMetadataOnOmero(keyValues,(OmeroRawImageServer)imageServer);
        if(replaceMetadata)
            wasSaved = OmeroRawScripting.sendMetadataOnOmeroAndUpdateKeyValues(keyValues,(OmeroRawImageServer)imageServer);

        if(wasSaved)
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d %s %s successfully sent to OMERO server",
                    keyValues.keySet().size(),
                    objectString,
                    (keyValues.keySet().size() == 1 ? "was" : "were")));
    }
}
