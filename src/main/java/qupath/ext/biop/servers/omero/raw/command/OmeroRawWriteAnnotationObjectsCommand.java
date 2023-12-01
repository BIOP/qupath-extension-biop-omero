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

package qupath.ext.biop.servers.omero.raw.command;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;

import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.roi.ROIWrapper;
import javafx.scene.control.CheckBox;
import javafx.scene.text.Font;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.ROIData;
import org.apache.commons.lang3.StringUtils;

import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawScripting;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawShapes;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.ext.biop.servers.omero.raw.utils.Utils;
import qupath.lib.gui.QuPathGUI;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.utils.GridPaneUtils;

import qupath.lib.objects.PathObject;


/**
 * Command to write path objects back to the OMERO server where the
 * current image is hosted.
 *
 * @author Melvin Gelbard
 *
 */
public class OmeroRawWriteAnnotationObjectsCommand implements Runnable {

    private final String title = "Sending annotations";
    private final double MAX_FONT_SIZE = 16.0;
    private final QuPathGUI qupath;

   public OmeroRawWriteAnnotationObjectsCommand(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    @Override
    public void run() {
        var viewer = qupath.getViewer();
        var server = viewer.getServer();

        // Check if OMERO server
        if (!(server instanceof OmeroRawImageServer)) {
            Dialogs.showErrorMessage(title, "The current image is not from OMERO!");
            return;
        }

        // build the GUI for import options
        GridPane pane = new GridPane();

        CheckBox cbAnnotationsMap = new CheckBox("Annotation measurements");
        cbAnnotationsMap.setSelected(false);

        CheckBox cbDetectionsMap = new CheckBox("Detection measurements");
        cbDetectionsMap.setSelected(false);

        CheckBox cbDeleteRois = new CheckBox("Delete existing tables & ROIs on OMERO");
        cbDeleteRois.setSelected(false);

        CheckBox cbDeleteMyRois = new CheckBox("Delete only files / ROIs I own");
        cbDeleteMyRois.setSelected(false);
        cbDeleteMyRois.setDisable(true);

        cbDeleteRois.selectedProperty().addListener((v, o, n) -> {
            cbDeleteMyRois.setDisable(!cbDeleteRois.isSelected());
            cbDeleteMyRois.setSelected(cbDeleteRois.isSelected());
        });


        Label frontMessageLb = new Label("Send all annotations with : ");
        frontMessageLb.setFont(new Font(MAX_FONT_SIZE));

        int row = 0;
        pane.add(frontMessageLb, 0, row++, 2, 1);
        pane.add(cbAnnotationsMap, 0, row++);
        pane.add(cbDetectionsMap, 0, row++);
        pane.add(new Label("--------------------------------"), 0, row++);
        pane.add(cbDeleteRois, 0, row++);
        pane.add(cbDeleteMyRois, 0, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog(title, pane))
            return;

        // get user choice
        boolean annotationMap = cbAnnotationsMap.isSelected();
        boolean deletePreviousExperiments = cbDeleteRois.isSelected();
        boolean detectionMap = cbDetectionsMap.isSelected();
        boolean deleteOnlyFilesIOwn = cbDeleteMyRois.isSelected();

        Collection<PathObject> objs = viewer.getHierarchy().getAnnotationObjects();

        // Output message if no annotation object was found
        if (objs.size() == 0) {
            Dialogs.showErrorMessage(title, "No annotation objects to send!");
            return;
        }

        // Confirm
        OmeroRawImageServer omeroServer = (OmeroRawImageServer) server;
        URI uri = server.getURIs().iterator().next();
        String objectString = "object" + (objs.size() == 1 ? "" : "s");
        pane = new GridPane();
        GridPaneUtils.addGridRow(pane, 0, 0, null, new Label(String.format("%d %s will be sent to:", objs.size(), objectString)));
        GridPaneUtils.addGridRow(pane, 1, 0, null, new Label(uri.toString()));
        var confirm = Dialogs.showConfirmDialog("Send " + (objs.size() == 0 ? "all " : "") + objectString, pane);
        if (!confirm)
            return;

        // get the current ROIs and tables
        List<FileAnnotationData> tmpFileList = new ArrayList<>();
        if(deletePreviousExperiments){
            tmpFileList = OmeroRawTools.readAttachments(omeroServer.getClient(), omeroServer.getId());
        }

        String ownerToDelete;
        if(deleteOnlyFilesIOwn)
            ownerToDelete = omeroServer.getClient().getLoggedInUser().getUserName();
        else ownerToDelete = Utils.ALL_USERS;

        // send annotations to OMERO
        List<ROIWrapper> roiWrappers = OmeroRawScripting.sendPathObjectsToOmero(omeroServer, objs, deletePreviousExperiments, ownerToDelete, false);
        if(roiWrappers == null){
            Dialogs.showErrorMessage("Sending annotations", "Cannot send annotations to OMERO. Please look at the log console to know more (View->Show log).");
            return;
        } else if(roiWrappers.isEmpty()){
            Dialogs.showWarningNotification("Sending annotations", "No annotations to send");
        } else {
            Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully",
                    String.format("%d %s %s successfully written to OMERO server",
                    objs.size(),
                    objectString,
                    (objs.size() == 1 ? "was" : "were")));
        }

        int nWrittenTables = 0;
        if(annotationMap) {
            // send table to OMERO
            if(OmeroRawScripting.sendAnnotationMeasurementTable(objs, omeroServer, qupath.getImageData())) nWrittenTables++;

            // send the corresponding csv file
            if(OmeroRawScripting.sendAnnotationMeasurementTableAsCSV(objs, omeroServer, qupath.getImageData())) nWrittenTables++;
        }

        if(detectionMap){
            // get detection objects
            Collection<PathObject> detections = viewer.getHierarchy().getDetectionObjects();

            // send detection measurement map
            if(detections.size() > 0) {
                // send table to OMERO
                if(OmeroRawScripting.sendDetectionMeasurementTable(detections, omeroServer, qupath.getImageData())) nWrittenTables++;

                // send the corresponding csv file
                if(OmeroRawScripting.sendDetectionMeasurementTableAsCSV(detections, omeroServer, qupath.getImageData())) nWrittenTables++;
            }
            else Dialogs.showErrorMessage(title, "No detection objects , cannot send detection map!");
        }

        // delete all previous ROIs and related tables (detection and annotations)
        if(deletePreviousExperiments) {
            String currentLoggedInUser = omeroServer.getClient().getLoggedInUser().getUserName();

            if(!deleteOnlyFilesIOwn)
                currentLoggedInUser = null;

            OmeroRawScripting.deleteAnnotationFiles(omeroServer, tmpFileList, currentLoggedInUser);
            OmeroRawScripting.deleteDetectionFiles(omeroServer, tmpFileList, currentLoggedInUser);
        }

        if(detectionMap || annotationMap)
            if(nWrittenTables > 0) {
                Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " written successfully", String.format("%d measurement %s were successfully sent to OMERO server",
                        nWrittenTables, nWrittenTables == 1 ? "table" : "tables"));
                int totalNumberOfTable = (detectionMap && annotationMap ? 4 : 2);
                if(nWrittenTables < totalNumberOfTable)
                    Dialogs.showInfoNotification(StringUtils.capitalize(objectString) + " writing failure", String.format("%d measurement %s were not sent to OMERO server",
                            totalNumberOfTable-nWrittenTables, totalNumberOfTable-nWrittenTables == 1 ? "table" : "tables"));
            }
            else
                Dialogs.showErrorNotification(StringUtils.capitalize(objectString) + " writing failure", "No measurement tables were sent to OMERO");
    }
}
