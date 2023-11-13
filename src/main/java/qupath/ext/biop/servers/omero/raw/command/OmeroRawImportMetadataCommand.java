package qupath.ext.biop.servers.omero.raw.command;

import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;

import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.OmeroRawScripting;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.projects.ProjectImageEntry;

import java.awt.image.BufferedImage;

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
        boolean keepMetadata = rbKeepMetadata.isSelected();
        boolean replaceMetadata = rbReplaceMetadata.isSelected();
        boolean deleteMetadata = rbDeleteMetadata.isSelected();

        // read keyValue from QuPath
        ProjectImageEntry<BufferedImage> entry = this.qupath.getProject().getEntry(this.qupath.getImageData());

        // inform user if some key-values already exists in QuPath
        if (keepMetadata) {
            // get the initial number of key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValues((OmeroRawImageServer) imageServer);
            OmeroRawScripting.addOmeroTags((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nQuPathKVAfterAdding = entry.getMetadataKeys().size();
            int nNewKV = nQuPathKVAfterAdding - nExistingKV;

            Dialogs.showInfoNotification(title, String.format("Keep %d metadata and add %d new %s", nExistingKV,
                    nNewKV,
                    (nNewKV <= 1 ? "KVP/tag" : "KVPs/tags")));
        }
        if(replaceMetadata) {
            // get the initial number of key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValuesAndUpdateMetadata((OmeroRawImageServer) imageServer);
            OmeroRawScripting.addOmeroTags((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nQuPathKVAfterAdding = entry.getMetadataKeys().size();
            int nNewKV = nQuPathKVAfterAdding - nExistingKV;

            Dialogs.showInfoNotification(title, String.format("Update %d metadata and add %d new %s", nExistingKV,
                    nNewKV,
                    (nNewKV <= 1 ? "KVP/tag" : "KVPs/tags")));
        }
        if(deleteMetadata) {
            // get the number of new key values
            int nExistingKV = entry.getMetadataKeys().size();

            // add new keyValues from omero
            OmeroRawScripting.addOmeroKeyValuesAndDeleteMetadata((OmeroRawImageServer) imageServer);
            OmeroRawScripting.addOmeroTags((OmeroRawImageServer) imageServer);

            // get the number of new key values
            int nNewKV = entry.getMetadataKeys().size();

            Dialogs.showInfoNotification(title, String.format("Delete %d previous metadata and add %d new %s", nExistingKV, nNewKV,
                    (nNewKV <= 1 ? "KVP/tag" : "KVPs/tags")));
        }
    }
}
