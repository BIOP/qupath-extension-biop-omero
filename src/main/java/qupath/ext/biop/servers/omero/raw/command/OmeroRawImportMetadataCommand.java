package qupath.ext.biop.servers.omero.raw.command;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;

import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Import key-values and tags from OMERO with a certain rule
 * - Keep existing and add new => only add in QuPath the new keys (if the key already exists, it is not imported)
 * - Replace existing and add new => add news keys and update those which already exist (i.e.replace the old value)
 * - Delete all and add new => delete all keys in QuPath and all those coming from OMERO.
 *
 * @author Rémy Dornier
 *
 */
public class OmeroRawImportMetadataCommand implements Runnable{
    private final String title = "Import KeyValues / Tags from OMERO";
    private final QuPathGUI qupath;

    public OmeroRawImportMetadataCommand(QuPathGUI qupath)  {
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
        pane.add(new Label("Select import options"), 0, row++, 2, 1);
        pane.add(rbKeepMetadata, 0, row++);
        pane.add(rbReplaceMetadata, 0, row++);
        pane.add(rbDeleteMetadata, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        // get user choice
        Utils.UpdatePolicy policy;
        if(rbReplaceMetadata.isSelected())
            policy = Utils.UpdatePolicy.UPDATE_KEYS;
        else if (rbDeleteMetadata.isSelected())
            policy = Utils.UpdatePolicy.DELETE_KEYS;
        else policy = Utils.UpdatePolicy.KEEP_KEYS;

        // read keyValue from QuPath
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());
        // get the initial number of key values
        int nExistingKV = entry.getMetadataKeys().size();

        // add new keyValues from omero
        Map<String, String> keyValueMap = OmeroRawScripting.addKeyValuesToQuPath((OmeroRawImageServer) imageServer, policy, true);
        List<String> tagList = OmeroRawScripting.addTagsToQuPath((OmeroRawImageServer) imageServer, Utils.UpdatePolicy.KEEP_KEYS, true);

        String message = "";
        switch(policy){
            case UPDATE_KEYS :
                message = "Keep %d metadata";
                break;
            case DELETE_KEYS:
                message = "Update %d metadata";
                break;
            case KEEP_KEYS:
                message = "Delete %d previous metadata";
        }

        Utils.infoLog(title, String.format(message + ", add %d new %s and add %d new %s", nExistingKV,
                keyValueMap == null ? 0 : keyValueMap.size(),
                ((keyValueMap != null && keyValueMap.size() <= 1) ? "KVP" : "KVPs"),
                tagList == null ? 0 : tagList.size(),
                ((tagList != null && tagList.size()<= 1) ? "tag" : "tags")), true);
    }
}
