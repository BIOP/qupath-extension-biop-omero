package qupath.ext.biop.servers.omero.raw.command;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.apache.commons.lang3.StringUtils;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.Map;


/**
 * Command to import QuPath metadata to OMERO server where the
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
            Utils.errorLog(title, "The current image is not from OMERO!", true);
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
        Utils.UpdatePolicy policy;
        if(rbReplaceMetadata.isSelected())
            policy = Utils.UpdatePolicy.UPDATE_KEYS;
       else if (rbDeleteMetadata.isSelected())
            policy = Utils.UpdatePolicy.DELETE_KEYS;
       else policy = Utils.UpdatePolicy.KEEP_KEYS;

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
            Utils.warnLog(title, "The current image does not contain any metadata", true);
            return;
        }

        // send metadata to OMERO
        Map<String, Map<String, String>> metadataSent = OmeroRawScripting.sendQPMetadataToOmero(keyValues, (OmeroRawImageServer) imageServer, policy, true);
        Map<String, String> tags = metadataSent.get(Utils.TAG_KEY);
        Map<String, String> kvps = metadataSent.get(Utils.KVP_KEY);
        if(!tags.isEmpty())
            Utils.infoLog("TAG"+ (tags.size() == 1 ? "":"s") + " written successfully", String.format("%d %s %s successfully sent to OMERO server",
                    tags.size(),
                    ("TAG"+ (tags.size() == 1 ? "":"s")),
                    (tags.size() == 1 ? "was" : "were")), true);


        if(!kvps.isEmpty())
            Utils.infoLog("KVP"+ (kvps.size() == 1 ? "":"s") + " written successfully",
                    String.format("%d %s %s successfully sent to OMERO server",
                            kvps.size(),
                            ("KVP"+ (kvps.size() == 1 ? "":"s")),
                            (kvps.size() == 1 ? "was" : "were")), true);
    }
}
