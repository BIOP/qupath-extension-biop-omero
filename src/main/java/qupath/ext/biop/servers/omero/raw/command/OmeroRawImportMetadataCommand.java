package qupath.ext.biop.servers.omero.raw.command;

import javafx.geometry.Orientation;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;

import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
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

        RadioButton rbKeepMetadata = new RadioButton("Only add new");
        rbKeepMetadata.setToggleGroup(group);

        RadioButton rbReplaceMetadata = new RadioButton("Update and add new");
        rbReplaceMetadata.setToggleGroup(group);
        rbReplaceMetadata.setSelected(true);

        RadioButton rbDeleteMetadata = new RadioButton("Delete and add new");
        rbDeleteMetadata.setToggleGroup(group);

        CheckBox cbTags = new CheckBox("Tags");
        cbTags.setSelected(true);

        CheckBox cbKeyValues = new CheckBox("Key-values");
        cbKeyValues.setSelected(true);
        cbKeyValues.selectedProperty().addListener((v, o, n) -> {
            if(!cbKeyValues.isSelected() && !cbTags.isSelected()) {
                rbKeepMetadata.setDisable(true);
                rbReplaceMetadata.setDisable(true);
                rbDeleteMetadata.setDisable(true);
            }else{
                rbKeepMetadata.setDisable(false);
                rbReplaceMetadata.setDisable(false);
                rbDeleteMetadata.setDisable(false);
            }
        });

        cbTags.selectedProperty().addListener((v, o, n) -> {
            if(!cbKeyValues.isSelected() && !cbTags.isSelected()) {
                rbKeepMetadata.setDisable(true);
                rbReplaceMetadata.setDisable(true);
                rbDeleteMetadata.setDisable(true);
            }else{
                rbKeepMetadata.setDisable(false);
                rbReplaceMetadata.setDisable(false);
                rbDeleteMetadata.setDisable(false);
            }
        });

        Separator separator2 = new Separator();
        separator2.setOrientation(Orientation.VERTICAL);

        int row = 0;
        pane.add(new Label("Select import options from OMERO"), 0, row++, 2, 1);
        pane.add(cbKeyValues, 0, row);
        pane.add(separator2, 1, row,1,4);
        pane.add(rbKeepMetadata, 2, row++);
        pane.add(rbReplaceMetadata, 2, row++);
        pane.add(cbTags, 0, row);
        pane.add(rbDeleteMetadata, 2, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        if(!cbKeyValues.isSelected() && !cbTags.isSelected()){
            Utils.warnLog(title, "No option were selected. Nothing imported", true);
            return;
        }

        // get user choice
        Utils.UpdatePolicy policy;
        if(rbReplaceMetadata.isSelected())
            policy = Utils.UpdatePolicy.UPDATE_KEYS;
        else if (rbDeleteMetadata.isSelected())
            policy = Utils.UpdatePolicy.DELETE_KEYS;
        else policy = Utils.UpdatePolicy.KEEP_KEYS;

        Utils.UpdatePolicy secondPolicy;
        if(cbKeyValues.isSelected() && cbTags.isSelected())
            secondPolicy = Utils.UpdatePolicy.KEEP_KEYS;
        else secondPolicy = policy;

        // read keyValue from QuPath
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());
        // get the initial number of key values
        int nExistingKV = entry.getMetadataKeys().size();

        // add new keyValues from omero
        Map<String, String> keyValueMap = new HashMap<>();
        List<String> tagList = new ArrayList<>();

        if(cbKeyValues.isSelected())
            keyValueMap = OmeroRawScripting.addKeyValuesToQuPath((OmeroRawImageServer) imageServer, policy, true);
        if(cbTags.isSelected())
            tagList = OmeroRawScripting.addTagsToQuPath((OmeroRawImageServer) imageServer, secondPolicy, true);

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
