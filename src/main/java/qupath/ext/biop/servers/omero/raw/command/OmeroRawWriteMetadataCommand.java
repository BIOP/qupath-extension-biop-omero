package qupath.ext.biop.servers.omero.raw.command;

import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * @author Rémy Dornier
 *
 */
public class OmeroRawWriteMetadataCommand  implements Runnable{
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawImportMetadataCommand.class);
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
            Utils.errorLog(logger, title, "The current image is not from OMERO!", true);
            return;
        }

        // get keys
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());
        Map<String, String> keyValues = entry.getMetadataMap();
        if (keyValues.size() == 0) {
            Utils.warnLog(logger, title, "The current image does not contain any metadata", true);
            return;
        }

        // build the GUI for import options
        GridPane pane = new GridPane();

        final ToggleGroup kvpGroup = new ToggleGroup();
        RadioButton rbKVPsKeepMetadata = new RadioButton("Only add new");
        rbKVPsKeepMetadata.setToggleGroup(kvpGroup);

        RadioButton rbKVPsReplaceMetadata = new RadioButton("Update and add new");
        rbKVPsReplaceMetadata.setToggleGroup(kvpGroup);
        rbKVPsReplaceMetadata.setSelected(true);

        RadioButton rbKVPsDeleteMetadata = new RadioButton("Delete and add new");
        rbKVPsDeleteMetadata.setToggleGroup(kvpGroup);

        final ToggleGroup tagGroup = new ToggleGroup();
        RadioButton rbTagKeepMetadata = new RadioButton("Only add new");
        rbTagKeepMetadata.setToggleGroup(tagGroup);
        rbTagKeepMetadata.setSelected(true);

        RadioButton rbTagUnlinkMetadata = new RadioButton("Unlink and add new");
        rbTagUnlinkMetadata.setToggleGroup(tagGroup);

        CheckBox cbKeyValues = new CheckBox("Key-values");
        cbKeyValues.setSelected(true);
        cbKeyValues.selectedProperty().addListener((v, o, n) -> {
            rbKVPsKeepMetadata.setDisable(!cbKeyValues.isSelected());
            rbKVPsReplaceMetadata.setDisable(!cbKeyValues.isSelected());
            rbKVPsDeleteMetadata.setDisable(!cbKeyValues.isSelected());
        });

        CheckBox cbTags = new CheckBox("Tags");
        cbTags.setSelected(true);
        cbTags.selectedProperty().addListener((v, o, n) -> {
            rbTagKeepMetadata.setDisable(!cbTags.isSelected());
            rbTagUnlinkMetadata.setDisable(!cbTags.isSelected());
        });

        int row = 0;
        pane.add(new Label("Select sending options to OMERO for "+keyValues.size() +" metadata"), 0, row++, 2, 1);

        pane.add(rbKVPsKeepMetadata, 1, row++);
        pane.add(cbKeyValues, 0, row);
        pane.add(rbKVPsReplaceMetadata, 1, row++);
        pane.add(rbKVPsDeleteMetadata, 1, row++);
        pane.add(new Label("--------------------------------"), 0, row);
        pane.add(new Label("--------------------------------"), 1, row++);
        pane.add(rbTagKeepMetadata, 1, row++);
        pane.add(cbTags, 0, row);
        pane.add(rbTagUnlinkMetadata, 1, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        Utils.UpdatePolicy kvpPolicy;
        if(!cbKeyValues.isSelected())
            kvpPolicy = Utils.UpdatePolicy.NO_UPDATE;
        else if(rbKVPsReplaceMetadata.isSelected())
            kvpPolicy = Utils.UpdatePolicy.UPDATE_KEYS;
        else if (rbKVPsDeleteMetadata.isSelected())
            kvpPolicy = Utils.UpdatePolicy.DELETE_KEYS;
        else kvpPolicy = Utils.UpdatePolicy.KEEP_KEYS;

        Utils.UpdatePolicy tagPolicy;
        if(!cbTags.isSelected())
            tagPolicy = Utils.UpdatePolicy.NO_UPDATE;
        else if (rbTagUnlinkMetadata.isSelected())
            tagPolicy = Utils.UpdatePolicy.DELETE_KEYS;
        else tagPolicy = Utils.UpdatePolicy.KEEP_KEYS;

        // send metadata to OMERO
        Map<String, Map<String, String>> metadataSent = OmeroRawScripting.sendQPMetadataToOmero(keyValues,
                (OmeroRawImageServer) imageServer, kvpPolicy, tagPolicy, true);
        Map<String, String> tags = metadataSent.get(Utils.TAG_KEY);
        Map<String, String> kvps = metadataSent.get(Utils.KVP_KEY);

        // give feedback
        if(!tags.isEmpty())
            Utils.infoLog(logger, "TAG"+ (tags.size() == 1 ? "":"s") + " written successfully",
                    String.format("%d %s %s successfully sent to OMERO server",
                    tags.size(),
                    ("TAG"+ (tags.size() == 1 ? "":"s")),
                    (tags.size() == 1 ? "was" : "were")), true);


        if(!kvps.isEmpty())
            Utils.infoLog(logger, "KVP"+ (kvps.size() == 1 ? "":"s") + " written successfully",
                    String.format("%d %s %s successfully sent to OMERO server",
                            kvps.size(),
                            ("KVP"+ (kvps.size() == 1 ? "":"s")),
                            (kvps.size() == 1 ? "was" : "were")), true);
    }
}
