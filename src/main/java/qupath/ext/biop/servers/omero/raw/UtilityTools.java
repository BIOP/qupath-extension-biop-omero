package qupath.ext.biop.servers.omero.raw;

import omero.gateway.model.ImageData;
import omero.gateway.model.TableData;
import omero.gateway.model.TableDataColumn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.objects.PathObject;
import qupath.lib.scripting.QP;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Private class regrouping all tools that are not restricted to OMERO.
 */
class UtilityTools {
    private final static Logger logger = LoggerFactory.getLogger(UtilityTools.class);
    protected static final String NUMERIC_FIELD_ID = "\\$";
    protected static final String IMAGE_ID_HEADER = NUMERIC_FIELD_ID + "Image_ID";


    /**
     * Convert a map < header, list_of_values > into a CSV file
     *
     * @param parentTable the map containing headers and values
     * @param name file name without the extension.
     * @return the saved CSV file
     */
    protected static File buildCSVFileFromListsOfStrings(LinkedHashMap<String, List<String>> parentTable, String name){
        StringBuilder csvContent = new StringBuilder();
        List<String> headers = new ArrayList<>(parentTable.keySet());

        if(!headers.isEmpty()) {
            int nRows = parentTable.get(headers.get(0)).size();

            // add the headers
            headers.forEach(item -> csvContent.append(item.replace(GeneralTools.micrometerSymbol(), "um").replace(NUMERIC_FIELD_ID, "")).append(","));
            csvContent.delete(csvContent.lastIndexOf(","), csvContent.lastIndexOf(","));
            csvContent.append("\n");

            // add the table
            for (int i = 0; i < nRows; i++) {
                for (String header : parentTable.keySet()) {
                    String item = parentTable.get(header).get(i);
                    csvContent.append(item.replace(NUMERIC_FIELD_ID, "")).append(",");
                }
                csvContent.delete(csvContent.lastIndexOf(","), csvContent.lastIndexOf(","));
                csvContent.append("\n");
            }

        } else {
            logger.warn("Build CSV file from list of string -- There is no measurement to add. Write an empty file");
        }

        String path = QP.buildFilePath(QP.PROJECT_BASE_DIR, name + ".csv");
        return createAndSaveFile(path, csvContent.toString());
    }

    /**
     * Convert a map < header, list_of_values > into an OMERO.table
     *
     * @param parentTable the map containing headers and values
     * @param client
     * @return the OMERO table
     */
    protected static TableData buildOmeroTableFromListsOfStrings(LinkedHashMap<String, List<String>> parentTable, OmeroRawClient client){
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();

        List<String> headers = new ArrayList<>(parentTable.keySet());
        if(headers.isEmpty()) {
            new TableData(columns, measurements);
        }

        int c = 0;

        // feature name ; here, the ImageData object is treated differently
        columns.add(new TableDataColumn("Image", c++, ImageData.class));

        // add all ImageData in the first column (read image from OMERO only once)
        Map<String, ImageData> mapImages = new HashMap<>();
        List<Object> imageDataField = new ArrayList<>();
        for (String item : parentTable.get(IMAGE_ID_HEADER)) {
            if(mapImages.containsKey(item)){
                imageDataField.add(mapImages.get(item));
            }else {
                ImageData imageData = OmeroRawTools.readOmeroImage(client, Long.parseLong(item.replace(NUMERIC_FIELD_ID, "")));
                imageDataField.add(imageData);
                mapImages.put(item, imageData);
            }
        }
        measurements.add(imageDataField);

        // get the table
        for (String header : parentTable.keySet()) {
            if(header.equals(IMAGE_ID_HEADER))
                continue;

            List<String> col = parentTable.get(header);
            // for OMERO.Table compatibility
            header = header.replace("Image", "Label");
            if (header.contains(NUMERIC_FIELD_ID)) {
                // feature name
                columns.add(new TableDataColumn(header.replace(GeneralTools.micrometerSymbol(),"um")
                        .replace(NUMERIC_FIELD_ID,"")
                        .replace("/","-"), c++, Double.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value => fill the entire column
                List<Object> feature = new ArrayList<>();
                for (String item : col) {
                    feature.add(Double.parseDouble(item.replace(NUMERIC_FIELD_ID,"")));
                }
                measurements.add(feature);
            } else {
                // feature name
                columns.add(new TableDataColumn(header.replace(GeneralTools.micrometerSymbol(),"um")
                        .replace("/","-"), c++, String.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value => fill the entire column
                List<Object> feature = new ArrayList<>(col);
                measurements.add(feature);
            }
        }
        return new TableData(columns, measurements);
    }

    /**
     * Convert a QuPath measurement table into a CSV file,
     * including the OMERO image ID on which the measurements are referring to.
     *
     * @param pathObjects
     * @param ob
     * @param imageId
     * @param name file name
     * @return CSV file of measurement table.
     */
    protected static File buildCSVFileFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, long imageId, String name) {
        StringBuilder tableString = new StringBuilder();

        // get the header
        tableString.append("Image_ID").append(",");
        List<String> allColumnNames = ob.getAllNames();
        for (String col : allColumnNames) {
            col = col.replace(GeneralTools.micrometerSymbol(),"um"); // remove "mu" character
            tableString.append(col).append(",");
        }
        tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
        tableString.append("\n");

        // get the table
        for (PathObject pathObject : pathObjects) {
            // add image id
            tableString.append(imageId).append(",");
            for (String col : ob.getAllNames()) {
                if (ob.isNumericMeasurement(col))
                    tableString.append(ob.getNumericValue(pathObject, col)).append(",");
                else
                    tableString.append(ob.getStringValue(pathObject, col)).append(",");
            }
            tableString.delete(tableString.lastIndexOf(","),tableString.lastIndexOf(","));
            tableString.append("\n");
        }
        String filename = QP.buildFilePath(QP.PROJECT_BASE_DIR, name + ".csv");
        return createAndSaveFile(filename, tableString.toString());
    }

    /**
     * Convert a QuPath measurement table to an OMERO table
     *
     * @param pathObjects
     * @param ob
     * @param client
     * @param imageId
     * @return The corresponding OMERO.Table
     */
    protected static TableData buildOmeroTableFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, OmeroRawClient client, long imageId) {
        List<TableDataColumn> columns = new ArrayList<>();
        List<List<Object>> measurements = new ArrayList<>();
        int i = 0;

        // add the first column with the image data (linkable on OMERO)
        columns.add(new TableDataColumn("Image ID",i++, ImageData.class));
        ImageData image = OmeroRawTools.readOmeroImage(client, imageId);
        List<Object> imageData = new ArrayList<>();

        for (PathObject ignored : pathObjects) {
            imageData.add(image);
        }
        measurements.add(imageData);

        // create formatted Lists of measurements to be compatible with omero.tables
        for (String col : ob.getAllNames()) {
            if (ob.isNumericMeasurement(col)) {
                // feature name
                columns.add(new TableDataColumn(col.replace("/","-"), i++, Double.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value for each pathObject
                List<Object> feature = new ArrayList<>();
                for (PathObject pathObject : pathObjects) {
                    feature.add(ob.getNumericValue(pathObject, col));
                }
                measurements.add(feature);
            }

            if (ob.isStringMeasurement(col)) {
                // feature name
                columns.add(new TableDataColumn(col.replace("/","-"), i++, String.class)); // OMERO table does not support "/" and remove "mu" character

                //feature value for each pathObject
                List<Object> feature = new ArrayList<>();
                for (PathObject pathObject : pathObjects) {
                    String strValue = ob.getStringValue(pathObject, col);
                    feature.add(strValue == null ? "null" : strValue);
                }
                measurements.add(feature);
            }
        }

        // create omero Table
        return new TableData(columns, measurements);
    }

    /**
     * Populate a map < header, list_of_values > with new measurements coming from a measurement table of new pathObjects.
     *
     * @param parentTable LinkedHashMap < header, List_of_measurements > to populate. Other type of maps will not work
     * @param ob QuPath Measurements table for the current image
     * @param pathObjects QuPath annotations or detections objects
     * @param imageId OMERO ID of the current image
     */
    protected static void buildListsOfStringsFromMeasurementTable(LinkedHashMap<String, List<String>> parentTable,
                                                                  ObservableMeasurementTableData ob,
                                                                  Collection<PathObject> pathObjects,
                                                                  long imageId){
        int headersSize = parentTable.keySet().size();
        if(headersSize == 0){
            // building the first measurement
            parentTable.put(IMAGE_ID_HEADER, new ArrayList<>());

            for(String header : ob.getAllNames()){
                if(ob.isNumericMeasurement(header))
                    parentTable.put(NUMERIC_FIELD_ID + header, new ArrayList<>());
                else
                    parentTable.put(header, new ArrayList<>());
            }
        } else if(headersSize != (ob.getAllNames().size() + 1)){
            Dialogs.showWarningNotification("Parent Table - Compatibility issue","Size of headers ("+ob.getAllNames().size()+
                    ") is different from existing table size ("+headersSize+"). Parent table is not populated");
            return;
        }

        // for all annotations = rows
        for (PathObject pathObject : pathObjects) {
            // add image id
            parentTable.get(IMAGE_ID_HEADER).add(NUMERIC_FIELD_ID + imageId);
            // get table headers
            List<String> tableHeaders = new ArrayList<>(parentTable.keySet());
            // for each column
            for (int i = 1; i < tableHeaders.size(); i++) {
                String col = tableHeaders.get(i);
                List<String> listedValues = parentTable.get(col);

                if(listedValues == null){
                    Dialogs.showErrorNotification("Parent Table - Compatibility issue","There is no columns named "+col);
                    throw new RuntimeException();
                }

                if (col.contains(NUMERIC_FIELD_ID)){
                    parentTable.get(col).add(NUMERIC_FIELD_ID +
                            ob.getNumericValue(pathObject, col.replace(NUMERIC_FIELD_ID,"")));
                } else {
                    parentTable.get(col).add(""+ob.getStringValue(pathObject, col)); // need to keep the empty space because of null values
                }
            }
        }
    }


    /**
     * Create a file in the given path, with the given content, and save it
     *
     * @param path location + filename of the path
     * @param content file content
     * @return the saved file
     */
    protected static File createAndSaveFile(String path, String content){
        // create the file locally
        File file = new File(path);

        try {
            // write the file
            BufferedWriter buffer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            buffer.write(content + "\n");

            // close the file
            buffer.close();

        } catch (IOException e) {
            Dialogs.showErrorNotification("Write CSV file", "An error has occurred when trying to save the csv file");
            logger.error("" + e);
            logger.error(OmeroRawTools.getErrorStackTraceAsString(e));
        }
        return file;
    }
}
