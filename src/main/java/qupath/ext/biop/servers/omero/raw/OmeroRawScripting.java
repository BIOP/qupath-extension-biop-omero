package qupath.ext.biop.servers.omero.raw;

import fr.igred.omero.Client;
import omero.gateway.exception.DSOutOfServiceException;
import javafx.collections.ObservableList;
import omero.gateway.facility.TablesFacility;
import omero.gateway.model.ChannelData;
import omero.gateway.model.DataObject;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.ROIData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.model.ChannelBinding;
import omero.model.Experimenter;
import omero.model.NamedValue;
import omero.model.RenderingDef;
import omero.rtypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.display.ChannelDisplayInfo;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.projects.ProjectImageEntry;
import qupath.lib.scripting.QP;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OmeroRawScripting {

    private static final String detectionFileBaseName = "QP detection table";
    private static final String annotationFileBaseName = "QP annotation table";
    private static final String summaryFileBaseName = "QP summary table";
    private static final String DEFAULT_KVP_NAMESPACE = "openmicroscopy.org/omero/client/mapAnnotation";
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawScripting.class);
    private final static String FILE_NAME_SPLIT_REGEX = "_([\\d]*-[\\d]*h[\\d].m[\\d].*)";


    /**
     * This method creates an instance of simple-omero-client object to get access to the full simple-omero-client API,
     * developed by Pierre Pouchin (<a href="https://github.com/GReD-Clermont/simple-omero-client">...</a>).
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     *
     * @return fr.igred.omero.Client object
     */
    public static Client getSimpleOmeroClientInstance(OmeroRawImageServer imageServer) throws DSOutOfServiceException {
        // get the current OmeroRawClient
        OmeroRawClient omerorawclient = imageServer.getClient();

        // build the simple-omero-client using the ID of the current session
        Client simpleClient = new Client();
        simpleClient.connect(omerorawclient.getServerURI().getHost(), omerorawclient.getServerURI().getPort(), omerorawclient.getGateway().getSessionId(omerorawclient.getGateway().getLoggedInUser()));

        return simpleClient;
    }


    /**
     * Import all ROIs linked to the current image from OMERO to QuPath.
     * <p>
     * <ul>
     * <li> Read ROIs from OMERO </li>
     * <li> Check if current annotations/detection have to be deleted or not </li>
     * <li> Add new pathObjects to the current image </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param removePathObjects Boolean to delete or keep pathObjects (annotations, detections) on the current image.
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects) {
        return importOmeroROIsToQuPath(imageServer, removePathObjects, null);
    }

    /**
     * Import ROIs owned by the specified owner and linked to the current image from OMERO to QuPath
     * <p>
     * <ul>
     * <li> Read ROIs from OMERO </li>
     * <li> Check if current annotations/detection have to be deleted or not </li>
     * <li> Add new pathObjects to the current image </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param removePathObjects Boolean to delete or keep pathObjects (annotations, detections) on the current image.
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer,
                                                                 boolean removePathObjects, String owner) {
        // read OMERO ROIs
        Collection<PathObject> pathObjects = imageServer.readPathObjects(owner);

        // get the current hierarchy
        PathObjectHierarchy hierarchy = QP.getCurrentHierarchy();

        // remove current annotations
        if (removePathObjects)
            hierarchy.removeObjects(hierarchy.getAnnotationObjects(), false);

        // add pathObjects to the current hierarchy
        if (!pathObjects.isEmpty()) {
            hierarchy.addObjects(pathObjects);
            hierarchy.resolveHierarchy();
        }

        return pathObjects;
    }


    /**
     * Send all QuPath objects (annotations and detections) to OMERO and deletes ROIs already stored on OMERO
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer) {
        return sendPathObjectsToOmero(imageServer, true, null);
    }

    /**
     * Send all QuPath objects (annotations and detections) to OMERO.
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        Collection<PathObject> pathObjects = QP.getAnnotationObjects();
        pathObjects.addAll(QP.getDetectionObjects());
        return sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO, owner);
    }

    /**
     * Send all QuPath annotation objects to OMERO, without deleting current ROIs on OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer) {
        return sendAnnotationsToOmero(imageServer, false, null);
    }


    /**
     * Send all QuPath annotation objects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIsOnOMERO, owner);
    }


    /**
     * Send all QuPath detection objects to OMERO without deleting existing ROIs.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer) {
        return sendDetectionsToOmero(imageServer, false, null);
    }


    /**
     * Send all QuPath detections to OMERO and delete existing ROIs is specified.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        Collection<PathObject> detections = QP.getDetectionObjects();
        return sendPathObjectsToOmero(imageServer, detections, deleteROIsOnOMERO, owner);
    }


    /**
     * Send a collection of QuPath objects (annotations and/or detections) to OMERO, without deleting existing ROIs
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects) {
        return sendPathObjectsToOmero(imageServer, pathObjects, false, null);
    }


    /**
     * Send a collection of pathObjects to OMERO.
     *
     * <p>
     * <ul>
     * <li> Convert pathObjects to OMERO ROIs </li>
     * <li> Delete all current ROIs on OMERO if explicitly asked </li>
     * <li> Send ROIs to the current image on OMERO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects,
                                                 boolean deleteROIsOnOMERO, String owner) {
        // convert pathObjects to OMERO ROIs
        List<ROIData> omeroROIs = OmeroRawShapes.createOmeroROIsFromPathObjects(pathObjects);

        // get omero client and image id to send ROIs to the correct image
        OmeroRawClient client = imageServer.getClient();
        long imageId = imageServer.getId();

        // delete ROIs
        if (deleteROIsOnOMERO) {
            // get existing ROIs
            List<ROIData> existingROIs = OmeroRawTools.readOmeroROIs(client, imageId);
            // write new ROIs
            boolean hasBeenWritten = OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);

            // filter only owner's ROIs
            List<ROIData> filteredROIs = OmeroRawShapes.filterByOwner(client, existingROIs, owner);

            // delete previous ROIs
            OmeroRawTools.deleteOmeroROIs(client, filteredROIs);

            return hasBeenWritten;
        } else {
            return OmeroRawTools.writeOmeroROIs(client, imageId, omeroROIs);
        }
    }


    /**
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMetadataOnOmero(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // Extract tags
        List<String> qpMetadataTags = new ArrayList<>();
        Map<String, String> qpMetadataKVP = new HashMap<>();
        for(String key:qpMetadata.keySet()) {
            if(key.equalsIgnoreCase(qpMetadata.get(key))){
                qpMetadataTags.add(key);
            }else{
                qpMetadataKVP.put(key, qpMetadata.get(key));
            }
        }

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(omeroKeyValuePairs, qpMetadataKVP);
        Map<String,String>  newKV = splitKeyValues.get(1);

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        // if all keys already exist, do not write an empty list
        boolean wasAdded = true;
        if(!newNV.isEmpty()) {
            // set annotation map
            MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
            newOmeroAnnotationMap.setContent(newNV);
            newOmeroAnnotationMap.setNameSpace(DEFAULT_KVP_NAMESPACE);

            // add key value on OMERO
            wasAdded = OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());
        }else{
            Dialogs.showInfoNotification("Save metadata on OMERO", "All metadata already exist on OMERO");
        }

        // add tags on OMERO
        wasAdded = wasAdded && sendTagsToOmero(qpMetadataTags, imageServer);

        return wasAdded;
    }


    /**
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMetadataOnOmeroAndDeleteKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // Extract tags
        List<String> qpMetadataTags = new ArrayList<>();
        Map<String, String> qpMetadataKVP = new HashMap<>();
        for(String key:qpMetadata.keySet()) {
            if(key.equalsIgnoreCase(qpMetadata.get(key))){
                qpMetadataTags.add(key);
            }else{
                qpMetadataKVP.put(key, qpMetadata.get(key));
            }
        }

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        qpMetadataKVP.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        boolean wasAdded = true;
        if(!newNV.isEmpty()) {
            // set annotation map
            MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
            newOmeroAnnotationMap.setContent(newNV);
            newOmeroAnnotationMap.setNameSpace(DEFAULT_KVP_NAMESPACE);

            // add key value pairs On OMERO
            wasAdded = OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());
        }else{
            Dialogs.showInfoNotification("Save metadata on OMERO", "No key values to send");
        }

        // delete current keyValues
        wasAdded = wasAdded && OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());

        // unlink tags on OMERO
        OmeroRawTools.unlinkTags(imageServer.getClient(), imageServer.getId());

        // add tags on OMERO
        wasAdded = wasAdded && sendTagsToOmero(qpMetadataTags, imageServer);

        return wasAdded;
    }


    /**
     * Send QuPath metadata to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent.
     * <br>
     * Existing keys on OMERO are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMetadataOnOmeroAndUpdateKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        // read current key-value on OMERO ==> used for the later deletion
        List<MapAnnotationData> omeroAnnotationMaps = OmeroRawTools.readKeyValues(imageServer.getClient(), imageServer.getId());

        // read OMERO key-values and check if they are unique
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null)
            return false;

        // Extract tags
        List<String> qpMetadataTags = new ArrayList<>();
        Map<String, String> qpMetadataKVP = new HashMap<>();
        for(String key:qpMetadata.keySet()) {
            if(key.equalsIgnoreCase(qpMetadata.get(key))){
                qpMetadataTags.add(key);
            }else{
                qpMetadataKVP.put(key, qpMetadata.get(key));
            }
        }

        // split QuPath metadata into those that already exist on OMERO and those that need to be added
        List<Map<String,String> > splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(omeroKeyValuePairs, qpMetadataKVP);
        Map<String,String>  newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);

        omeroKeyValuePairs.forEach((keyToUpdate, valueToUpdate) -> {
            for (String updated : existingKV.keySet())
                if (keyToUpdate.equals(updated))
                    omeroKeyValuePairs.replace(keyToUpdate, valueToUpdate, existingKV.get(keyToUpdate));
        });

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        omeroKeyValuePairs.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
        newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));

        boolean wasAdded = true;
        if(!newNV.isEmpty()) {
            // set annotation map
            MapAnnotationData newOmeroAnnotationMap = new MapAnnotationData();
            newOmeroAnnotationMap.setContent(newNV);
            newOmeroAnnotationMap.setNameSpace(DEFAULT_KVP_NAMESPACE);

            // add key value pairs On OMERO
            wasAdded = OmeroRawTools.addKeyValuesOnOmero(newOmeroAnnotationMap, imageServer.getClient(), imageServer.getId());
        }else{
            Dialogs.showInfoNotification("Save metadata on OMERO", "No key values to send");
        }

        // delete current keyValues
        wasAdded = wasAdded && OmeroRawTools.deleteKeyValuesOnOmero(omeroAnnotationMaps, imageServer.getClient());

        // add tags on OMERO
        wasAdded = wasAdded && sendTagsToOmero(qpMetadataTags, imageServer);

        return wasAdded;
    }


    /**
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
     */
    public static void addMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QP.getProjectEntry();

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split key value pairs into those that already exist in QuPath and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);

        // add metadata
        newKV.forEach(entry::putMetadataValue);
    }


    /**
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void addOmeroKeyValues(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);
        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return;

        // add metadata
        addMetadata(omeroKeyValuePairs);
    }


    /**
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
     */
    public static void addAndUpdateMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QP.getProjectEntry();

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();

        // split key value pairs metadata into those that already exist in QuPath and those that need to be added
        List<Map<String,String>> splitKeyValues = OmeroRawTools.splitNewAndExistingKeyValues(qpMetadata, keyValues);
        Map<String,String> newKV = splitKeyValues.get(1);
        Map<String,String> existingKV = splitKeyValues.get(0);
        Map<String,String> updatedKV = new HashMap<>();

        // update metadata
        qpMetadata.forEach((keyToUpdate, valueToUpdate) -> {
            String newValue = valueToUpdate;
            for (String updated : existingKV.keySet()) {
                if (keyToUpdate.equals(updated)) {
                    newValue = existingKV.get(keyToUpdate);
                    break;
                }
            }
            updatedKV.put(keyToUpdate, newValue);
        });

        // delete metadata
        entry.clearMetadata();

        // add metadata
        updatedKV.forEach(entry::putMetadataValue);
        newKV.forEach(entry::putMetadataValue);
    }


    /**
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : NO </li>
     * <li> updated : YES </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void addOmeroKeyValuesAndUpdateMetadata(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValuePairs = importOmeroKeyValues(imageServer);

        if(omeroKeyValuePairs == null || omeroKeyValuePairs.isEmpty())
            return;

        // add and update metadata
        addAndUpdateMetadata(omeroKeyValuePairs);
    }


    /**
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void addOmeroKeyValuesAndDeleteMetadata(OmeroRawImageServer imageServer) {
        // read OMERO key-values and check if they are unique. If not, stop the process
        Map<String,String> omeroKeyValues = importOmeroKeyValues(imageServer);

        if(omeroKeyValues == null || omeroKeyValues.isEmpty())
            return;

        // add and delete metadata
        addAndDeleteMetadata(omeroKeyValues);
    }


    /**
     * Add new QuPath metadata to the current image in the QuPath project.
     * <br>
     * Existing keys in QuPath are :
     * <p>
     * <ul>
     * <li> deleted : YES </li>
     * <li> updated : NO </li>
     * </ul>
     * <p>
     *
     * @param keyValues map of key-values
     */
    public static void addAndDeleteMetadata(Map<String,String> keyValues) {
        // get project entry
        ProjectImageEntry<BufferedImage> entry = QP.getProjectEntry();

        // delete metadata
        entry.clearMetadata();

        // add new metadata
        keyValues.forEach(entry::putMetadataValue);
    }


    /**
     * Read, from OMERO, Key-Value pairs attached to the current image and check if all keys are unique. If they are not unique, no Key-Values are returned.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return map of OMERO Key-Value pairs
     */
    public static Map<String,String> importOmeroKeyValues(OmeroRawImageServer imageServer) {
        // read current key-value on OMERO
        List<NamedValue> currentOmeroKeyValues = OmeroRawTools.readKeyValuesAsNamedValue(imageServer.getClient(), imageServer.getId());

        if (currentOmeroKeyValues.isEmpty()) {
            Dialogs.showWarningNotification("Read key values on OMERO", "The current image does not have any KeyValues on OMERO");
            return new HashMap<>();
        }

        // check unique keys
        boolean uniqueOmeroKeys = OmeroRawTools.checkUniqueKeyInAnnotationMap(currentOmeroKeyValues);

        if (!uniqueOmeroKeys) {
            Dialogs.showErrorMessage("Keys not unique", "There are at least two identical keys on OMERO. Please make each key unique");
            return null;
        }

        return currentOmeroKeyValues.stream().collect(Collectors.toMap(e->e.name, e->e.value));
    }


    /**
     * Read, from OMERO, tags attached to the current image and add them as QuPath metadata fields
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return list of OMERO tags.
     */
    public static List<String> addOmeroTags(OmeroRawImageServer imageServer) {
        // read tags
        List<TagAnnotationData> omeroTagAnnotations = OmeroRawTools.readTags(imageServer.getClient(), imageServer.getId());

        // collect and convert to list
        List<String> omeroTagValues = omeroTagAnnotations.stream().map(TagAnnotationData::getTagValue).collect(Collectors.toList());

        // create a map with equals key and values
        Map<String,String> omeroTagMap =  omeroTagValues.stream().collect(Collectors.toMap(e->e, e->e));

        // add metadata
        addMetadata(omeroTagMap);

        return omeroTagValues;
    }


    /**
     * Send a list of tags to OMERO. If tags are already attached to the image, these tags are not sent.
     *
     * @param tags List of tags to add to the image
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if tags have been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendTagsToOmero(List<String> tags, OmeroRawImageServer imageServer){
        // get current OMERO tags
        List<TagAnnotationData> omeroTagAnnotations = OmeroRawTools.readTags(imageServer.getClient(), imageServer.getId());
        List<String> currentTags = omeroTagAnnotations.stream().map(TagAnnotationData::getTagValue).collect(Collectors.toList());

        // remove all existing tags
        tags.removeAll(currentTags);

        if(tags.isEmpty()) {
            Dialogs.showInfoNotification("Sending tags", "All tags are already existing on OMERO.");
            return true;
        }

        boolean wasAdded = true;
        List<TagAnnotationData> tagsToAdd = new ArrayList<>();
        List<TagAnnotationData> groupTags = OmeroRawTools.readUserTags(imageServer.getClient());

        for(String tag:tags) {
            if(tagsToAdd.stream().noneMatch(e-> e.getTagValue().equalsIgnoreCase(tag))){
                TagAnnotationData newOmeroTagAnnotation;
                List<TagAnnotationData> matchedTags = groupTags.stream().filter(e -> e.getTagValue().equalsIgnoreCase(tag)).collect(Collectors.toList());
                if(matchedTags.isEmpty()){
                    newOmeroTagAnnotation = new TagAnnotationData(tag);
                } else {
                    newOmeroTagAnnotation = matchedTags.get(0);
                }
                // find if the requested tag already exists
                tagsToAdd.add(newOmeroTagAnnotation);
            }
        }

        for(TagAnnotationData tag:tagsToAdd) {
            // send tag to OMERO
            wasAdded = wasAdded && OmeroRawTools.addTagsOnOmero(tag, imageServer.getClient(), imageServer.getId());
        }

        return wasAdded;
    }


    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table
     *
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param tableName Name of the OMERO.table
     * @param deletePreviousTable Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    private static boolean sendMeasurementTableToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer,
                                                       ImageData<BufferedImage> imageData, String tableName,
                                                       boolean deletePreviousTable, String owner){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        OmeroRawClient client = imageServer.getClient();
        Long imageId = imageServer.getId();

        // convert the table to OMERO.table
        TableData table = OmeroRawTools.convertMeasurementTableToOmeroTable(pathObjects, ob, client, imageId);

        if(deletePreviousTable){
            Collection<FileAnnotationData> tables = OmeroRawTools.readTables(client, imageId);
            boolean hasBeenSent = OmeroRawTools.addTableToOmero(table, tableName, client, imageId);
            String[] groups = tableName.split(FILE_NAME_SPLIT_REGEX);
            String matchedTableName;
            if(groups.length == 0){
                matchedTableName = tableName.substring(0, tableName.lastIndexOf("_"));
            }else{
                matchedTableName = groups[0];
            }
            deletePreviousFileVersions(client, tables, matchedTableName, TablesFacility.TABLES_MIMETYPE, owner);

            return hasBeenSent;
        } else
            // send the table to OMERO
            return OmeroRawTools.addTableToOmero(table, tableName, client, imageId);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param tableName Name of the table to upload
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMeasurementTableToOmero(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String tableName){
        return sendMeasurementTableToOmero(annotationObjects, imageServer, imageData, tableName, false, null);
    }

    /**
     * Send all annotations measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTable(QP.getAnnotationObjects(), imageServer, imageData);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTable(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the table name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableToOmero(annotationObjects, imageServer, imageData, name);
    }

    /**
     * Send all detections measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTable(QP.getDetectionObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table with a default table name referring to detections
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTable(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the table name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableToOmero(detectionObjects, imageServer, imageData, name);
    }


    /**
     * Send all annotations measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendAnnotationMeasurementTableAsCSV(QP.getAnnotationObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param annotationObjects QuPath annotation objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendAnnotationMeasurementTableAsCSV(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the file name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableAsCSVToOmero(annotationObjects, imageServer, imageData, name);
    }


    /**
     * Send all detections measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        return sendDetectionMeasurementTableAsCSV(QP.getDetectionObjects(), imageServer, imageData);
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendDetectionMeasurementTableAsCSV(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        // set the file name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();
        return sendMeasurementTableAsCSVToOmero(detectionObjects, imageServer, imageData, name);
    }

    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param pathObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the file to upload
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, String filename){
        return sendMeasurementTableAsCSVToOmero(pathObjects, imageServer, imageData, filename, false, null);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table
     *
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the CSV file
     * @param deletePreviousTable Delete or not all previous versions of csv measurements tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    private static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer,
                                                            ImageData<BufferedImage> imageData, String filename,
                                                            boolean deletePreviousTable, String owner){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        // get the path
        String path = QPEx.getQuPath().getProject().getPath().getParent().toString();

        // build the csv file from the measurement table
        File file = OmeroRawTools.buildCSVFileFromMeasurementTable(pathObjects, ob, imageServer.getId(), filename, path);

        boolean hasBeenSent = false;
        if (file.exists()) {
            OmeroRawClient client = imageServer.getClient();
            long imageId = imageServer.getId();

            if (deletePreviousTable) {
                Collection<FileAnnotationData> attachments = OmeroRawTools.readAttachments(client, imageId);
                hasBeenSent = OmeroRawTools.addAttachmentToOmero(file, client, imageId);
                String[] groups = filename.split(FILE_NAME_SPLIT_REGEX);
                String matchedFileName;
                if(groups.length == 0){
                    matchedFileName = filename.substring(0, filename.lastIndexOf("_"));
                }else{
                    matchedFileName = groups[0];
                }
                deletePreviousFileVersions(client, attachments, matchedFileName, FileAnnotationData.MS_EXCEL, owner);

            } else
                // add the csv file to OMERO
                hasBeenSent = OmeroRawTools.addAttachmentToOmero(file, client, imageId);

            // delete the temporary file
            file.delete();
        }
        return hasBeenSent;
    }


    /**
     * Populate a map "header, List_of_measurements" with new measurements coming from a measurement table of new pathObjects.
     *
     * @param parentTable LinkedHashMap "header, List_of_measurements" to populate. Other type of maps will not work
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     */
    public static void addMeasurementsToParentTable(LinkedHashMap<String, List<String>> parentTable,
                                                    Collection<PathObject> pathObjects, OmeroRawImageServer imageServer,
                                                    ImageData<BufferedImage> imageData){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        // convert measurements to lists of strings to build the parent table
        UtilityTools.buildListsOfStringsFromMeasurementTable(parentTable, ob, pathObjects, imageServer.getId());
    }

    /**
     * Send the summary map "header, List_of_measurements" to OMERO as an CSV file attached to the parent containers.
     * <p>
     * <ul>
     * <li> IMPORTANT : The attached file is uploaded ONCE on the OMERO database. The same file is then linked to
     * the multiple parent containers. If one deletes the file, all the links will also be deleted</li>
     * </ul>
     * <p>
     *
     * @param parentTable LinkedHashMap "header, List_of_measurements" to populate. Other type of maps will not work
     * @param client OMERO Client Object to handle OMERO connection
     * @param parents Collection of parent container on OMERO to link the file to
     * @param deletePreviousTable True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendParentMeasurementTableAsCSV(LinkedHashMap<String, List<String>> parentTable,
                                                          OmeroRawClient client, Collection<DataObject> parents,
                                                          boolean deletePreviousTable){
        return sendParentMeasurementTableAsCSV(parentTable, client, parents, deletePreviousTable, null);
    }


    /**
     * Send the summary map "header, List_of_measurements" to OMERO as an CSV file attached to the parent containers.
     * <p>
     * <ul>
     * <li> IMPORTANT : The attached file is uploaded ONCE on the OMERO database. The same file is then linked to
     * the multiple parent containers. If one deletes the file, all the links will also be deleted</li>
     * </ul>
     * <p>
     * 
     * @param parentTable LinkedHashMap "header, List_of_measurements" to populate. Other type of maps will not work
     * @param client OMERO Client Object to handle OMERO connection
     * @param parents Collection of parent container on OMERO to link the file to
     * @param deletePreviousTable True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendParentMeasurementTableAsCSV(LinkedHashMap<String, List<String>> parentTable,
                                                          OmeroRawClient client, Collection<DataObject> parents,
                                                          boolean deletePreviousTable, String owner){
        // set the file name
        String filename = summaryFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();

        // build the CSV parent table
        File parentCSVFile = UtilityTools.buildCSVFileFromListsOfStrings(parentTable, filename);

        FileAnnotationData attachedFile = null;
        if (parentCSVFile.exists()) {
            // create an annotation file
            attachedFile = new FileAnnotationData(parentCSVFile);

            // loop over all parents if images comes from more than one dataset
            for(DataObject parent : parents) {
                if(attachedFile != null) {
                    if (deletePreviousTable) {
                        // get all attachments before adding new ones
                        Collection<FileAnnotationData> attachments = OmeroRawTools.readAttachments(client, parent);

                        // link the file if it has already been uploaded once. Upload it otherwise
                        if (attachedFile.getFileID() > 0)
                            attachedFile = linkFile(client, attachedFile, parent);
                        else
                            attachedFile = OmeroRawTools.addAttachmentToOmero(parentCSVFile, client, parent);

                        // delete previous files
                        if (attachedFile != null){
                            String[] groups = filename.split(FILE_NAME_SPLIT_REGEX);
                            String matchedFileName;
                            if(groups.length == 0){
                                matchedFileName = filename.substring(0, filename.lastIndexOf("_"));
                            }else{
                                matchedFileName = groups[0];
                            }
                            deletePreviousFileVersions(client, attachments, matchedFileName, FileAnnotationData.MS_EXCEL, owner);
                        }
                    } else {
                        // link the file if it has already been uploaded once. Upload it otherwise
                        if (attachedFile.getFileID() > 0)
                            attachedFile = linkFile(client, attachedFile, parent);
                        else
                            attachedFile = OmeroRawTools.addAttachmentToOmero(parentCSVFile, client, parent);
                    }
                }
            }
            // delete the temporary file
            parentCSVFile.delete();
        }
        return attachedFile != null;
    }


    /**
     * Send the summary map "header, List_of_measurements" to OMERO as an OMERO.table attached to the parent container
     * <p>
     * <ul>
     * <li> IMPORTANT : The attached file is uploaded ONCE on the OMERO database. The same file is then linked to
     * the multiple parent containers. If one deletes the file, all the links will also be deleted</li>
     * </ul>
     * <p>
     *
     * @param parentTable LinkedHashMap "header, List_of_measurements" to populate. Other type of maps will not work
     * @param client OMERO Client Object to handle OMERO connection
     * @param parents Collection of parent containers on OMERO to link the file to
     * @param deletePreviousTable True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendParentMeasurementTableAsOmeroTable(LinkedHashMap<String, List<String>> parentTable,
                                                                 OmeroRawClient client, Collection<DataObject> parents,
                                                                 boolean deletePreviousTable){
        return sendParentMeasurementTableAsOmeroTable(parentTable, client, parents, deletePreviousTable, null);
    }


    /**
     * Send the summary map "header, List_of_measurements" to OMERO as an OMERO.table attached to the parent container
     * <p>
     * <ul>
     * <li> IMPORTANT : The attached file is uploaded ONCE on the OMERO database. The same file is then linked to
     * the multiple parent containers. If one deletes the file, all the links will also be deleted</li>
     * </ul>
     * <p>
     *
     * @param parentTable LinkedHashMap "header, List_of_measurements" to populate. Other type of maps will not work
     * @param client OMERO Client Object to handle OMERO connection
     * @param parents Collection of parent containers on OMERO to link the file to
     * @param deletePreviousTable True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     */
    public static boolean sendParentMeasurementTableAsOmeroTable(LinkedHashMap<String, List<String>> parentTable,
                                                                 OmeroRawClient client, Collection<DataObject> parents,
                                                                 boolean deletePreviousTable, String owner){
        // set the file name
        String filename = summaryFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                OmeroRawTools.getCurrentDateAndHour();

        // build the OMERO.table parent table
        TableData omeroTable = UtilityTools.buildOmeroTableFromListsOfStrings(parentTable, client);
        FileAnnotationData attachedFile = null;

        // loop over all parents if images comes from more than one dataset
        for(DataObject parent : parents) {
            if (deletePreviousTable) {
                // get all attachments before adding new ones
                Collection<FileAnnotationData> attachments = OmeroRawTools.readAttachments(client, parent);

                // link the file if it has already been uploaded once. Upload it otherwise
                if (attachedFile != null && attachedFile.getFileID() > 0)
                    attachedFile = linkFile(client, attachedFile, parent);
                else {
                    TableData tableData = OmeroRawTools.addTableToOmero(omeroTable, filename, client, parent);
                    if (tableData != null) {
                        // read the annotation file
                        attachedFile = OmeroRawTools.readAttachments(client, parent).stream()
                                .filter(e -> e.getFileID() == tableData.getOriginalFileId())
                                .findFirst()
                                .get();
                    }
                }

                // delete previous files
                if (attachedFile != null) {
                    String[] groups = filename.split(FILE_NAME_SPLIT_REGEX);
                    String matchedFileName;
                    if(groups.length == 0){
                        matchedFileName = filename.substring(0, filename.lastIndexOf("_"));
                    }else{
                        matchedFileName = groups[0];
                    }
                    deletePreviousFileVersions(client, attachments, matchedFileName, TablesFacility.TABLES_MIMETYPE, owner);
                }

            } else {
                // link the file if it has already been uploaded once. Upload it otherwise
                if (attachedFile != null && attachedFile.getFileID() > 0)
                    attachedFile = linkFile(client, attachedFile, parent);
                else {
                    TableData tableData = OmeroRawTools.addTableToOmero(omeroTable, filename, client, parent);
                    if (tableData != null) {
                        attachedFile = OmeroRawTools.readAttachments(client, parent).stream()
                                .filter(e -> e.getFileID() == tableData.getOriginalFileId())
                                .findFirst()
                                .get();
                    }
                }
            }
        }
        return attachedFile != null;
    }


    /**
     * Return the files as FileAnnotationData attached to the current image from OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return a list of read FileAnnotationData
     */
    public static List<FileAnnotationData> readFilesAttachedToCurrentImageOnOmero(OmeroRawImageServer imageServer){
        return OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
    }

    /**
     * Link a FileAnnotationData to an OMERO container
     * The FileAnnotationData must already have a valid ID on OMERO (i.e. already existing in the OMERO database)
     *
     * @param client OmeroRawClient object to handle OMERO connection
     * @param fileAnnotationData annotation to link
     * @param container on OMERO
     * @return the linked FileAnnotationData
     */
    protected static FileAnnotationData linkFile(OmeroRawClient client, FileAnnotationData fileAnnotationData, DataObject container){
        return (FileAnnotationData) OmeroRawTools.linkAnnotationToOmero(client, fileAnnotationData, container);
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer.getClient(), files, name, FileAnnotationData.MS_EXCEL, null);
        deletePreviousFileVersions(imageServer.getClient(), files, name, TablesFacility.TABLES_MIMETYPE, null);
    }


    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     */
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        deleteAnnotationFiles(imageServer, files, null);
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     */
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files, String owner){
        String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer.getClient(), files, name, FileAnnotationData.MS_EXCEL, owner);
        deletePreviousFileVersions(imageServer.getClient(), files, name, TablesFacility.TABLES_MIMETYPE, owner);
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer.getClient(), files, name, FileAnnotationData.MS_EXCEL, null);
        deletePreviousFileVersions(imageServer.getClient(), files, name, TablesFacility.TABLES_MIMETYPE, null);
    }

    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     */
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        deleteDetectionFiles(imageServer, files, null);
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     */
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files, String owner){
        String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
        deletePreviousFileVersions(imageServer.getClient(), files, name, FileAnnotationData.MS_EXCEL, owner);
        deletePreviousFileVersions(imageServer.getClient(), files, name, TablesFacility.TABLES_MIMETYPE, owner);
    }


    /**
     * Delete all previous version of a file, identified by the name given in parameters. This name may or may not be the
     * full name of the files to delete.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param name contained in the table/file name to delete (i.e. filtering item). It may be a part of the full table/file name
     */
    public static void deleteFiles(OmeroRawImageServer imageServer, String name){
        List<FileAnnotationData> files = OmeroRawTools.readAttachments(imageServer.getClient(), imageServer.getId());
        deletePreviousFileVersions(imageServer.getClient(), files, name, null, null);
    }

    /**
     * Delete all previous version of tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the given table name in the list of files.
     *
     * @param client Omero client
     * @param files List of files to browse
     * @param name Table name that files name must contain to be deleted (i.e. filtering item)
     * @param format file format to look for
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     */
    private static void deletePreviousFileVersions(OmeroRawClient client, Collection<FileAnnotationData> files, String name, String format, String owner){
        if(!files.isEmpty()) {
            List<FileAnnotationData> previousTables = files.stream()
                    .filter(e -> e.getFileName().contains(name) &&
                            (format == null || format.isEmpty() || e.getFileFormat().equals(format) || e.getOriginalMimetype().equals(format)))
                    .collect(Collectors.toList());

            List<FileAnnotationData> filteredTables = new ArrayList<>();
            Map<Long, String> ownerMap = new HashMap<>();

            if(owner != null && !owner.isEmpty()) {
                for (FileAnnotationData previousTable : previousTables) {
                    // get the ROI's owner ID
                    long ownerId = previousTable.getOwner().getId();
                    String tableOwner;

                    // get the ROI's owner
                    if (ownerMap.containsKey(ownerId)) {
                        tableOwner = ownerMap.get(ownerId);
                    } else {
                        Experimenter ownerObj = OmeroRawTools.getOmeroUser(client, ownerId, "");
                        tableOwner = ownerObj.getOmeName().getValue();
                        ownerMap.put(ownerId, tableOwner);
                    }

                    if (tableOwner.equals(owner))
                        filteredTables.add(previousTable);
                }
            }else{
                filteredTables = previousTables;
            }

            if (!filteredTables.isEmpty())
                OmeroRawTools.deleteFiles(client, filteredTables);
            else logger.warn("Sending tables : No previous table attached to the image");
        }
    }


    /**
     * Set the minimum and maximum display range value of each channel on QuPath, based on OMERO settings.<br>
     * QuPath image and thumbnail are updated accordingly.<br>
     * Channel indices are taken as reference.
     *
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsDisplayRangeFromOmeroChannel(OmeroRawImageServer imageServer) {
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        ImageData<BufferedImage> imageData = QPEx.getQuPath().getImageData();
        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get the min-max per channel from OMERO
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            double minDynamicRange = binding.getInputStart().getValue();
            double maxDynamicRange = binding.getInputEnd().getValue();

            // set the dynamic range
            QPEx.setChannelDisplayRange(imageData, c, minDynamicRange, maxDynamicRange);
        }

        // Update the thumbnail
        updateQuPathThumbnail();
    }


    /**
     * Set the color of each channel on QuPath, based on OMERO settings.<br>
     * QuPath image and thumbnail are updated accordingly.<br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsColorFromOmeroChannel(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        List<Integer> colors = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            // get OMERO channels color
            colors.add(new Color(binding.getRed().getValue(),binding.getGreen().getValue(), binding.getBlue().getValue(), binding.getAlpha().getValue()).getRGB());
        }

        // set QuPath channels color
        QPEx.setChannelColors(QPEx.getQuPath().getImageData(), colors.toArray(new Integer[0]));

        // Update the thumbnail
        updateQuPathThumbnail();

    }

    /**
     * Update QuPath thumbnail
     */
    private static void updateQuPathThumbnail(){
        try {
            // saved changes
            QPEx.getQuPath().getProject().syncChanges();

            // get the current image data
            ImageData<BufferedImage> newImageData = QPEx.getQuPath().getViewer().getImageDisplay().getImageData();

            // generate thumbnail
            BufferedImage thumbnail = QPEx.getQuPath().getViewer().getRGBThumbnail();

            // get and save the new thumbnail
            ProjectImageEntry<BufferedImage> entry = QPEx.getQuPath().getProject().getEntry(newImageData);
            entry.setThumbnail(thumbnail);
            entry.saveImageData(newImageData);

            // save changes
            QPEx.getQuPath().getProject().syncChanges();
        }catch (IOException e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Set the name of each channel on QuPath, based on OMERO settings.
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     */
    public static void setChannelsNameFromOmeroChannel(OmeroRawImageServer imageServer){
        // get the number of the channels in OMERO
        List<ChannelData> omeroChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId());

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroChannels.size() != qpChannels.size()){
            Dialogs.showWarningNotification("Channel settings", "The image on OMERO has not the same number of channels ("+omeroChannels.size()+" as the one in QuPath ("+imageServer.nChannels()+")");
            return;
        }

        List<String> names = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get OMERO channels name
            names.add(omeroChannels.get(c).getName());
        }

        // set QuPath channels name
        QPEx.setChannelNames(QPEx.getQuPath().getImageData(), names.toArray(new String[0]));
    }



    /**
     * Set the minimum and maximum display range value of each channel on OMERO, based on QuPath settings.<br>
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsDisplayRangeToOmero(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("OMERO channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return false;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroNChannels+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            double minDisplayRange = qpChannels.get(c).getMinDisplay();
            double maxDisplayRange = qpChannels.get(c).getMaxDisplay();

            // set the rendering settings with new min/max values
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            binding.setInputStart(rtypes.rdouble(minDisplayRange));
            binding.setInputEnd(rtypes.rdouble(maxDisplayRange));
        }

        // update the image on OMERO first
        boolean updateImageDisplay = OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), renderingSettings);

        // update the image thumbnail on OMERO
        boolean updateThumbnail = OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(),imageServer.getId(),renderingSettings.getId().getValue());

        return updateImageDisplay && updateThumbnail;
    }


    /**
     * Set the name of each channel on OMERO, based on QuPath settings.
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsNameToOmero(OmeroRawImageServer imageServer){
        // get the number of the channels in OMERO
        List<ChannelData> omeroChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId());

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroChannels.size() != qpChannels.size()){ // can use imageServer.nChannels() to get the real number of channel
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroChannels.size()+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            String qpChName = imageServer.getChannel(c).getName();

            // set the rendering settings with new min/max values
            omeroChannels.get(c).setName(qpChName);
        }

        // update the image on OMERO first
        return OmeroRawTools.updateObjectsOnOmero(imageServer.getClient(), omeroChannels.stream().map(ChannelData::asIObject).collect(Collectors.toList()));
    }


    /**
     * Set the color of each channel on OMERO, based on QuPath settings.
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsColorToOmero(OmeroRawImageServer imageServer){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getId());

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("OMERO channel settings", "Cannot access to rendering settings of the image " + imageServer.getId());
            return false;
        }

        // get the number of the channels in OMERO
        int omeroNChannels = OmeroRawTools.readOmeroChannels(imageServer.getClient(), imageServer.getId()).size();

        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        // check if both images has the same number of channels
        if(omeroNChannels != qpChannels.size()){ // can use imageServer.nChannels() to get the real number of channel
            Dialogs.showWarningNotification("OMERO channel settings", "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroNChannels+")");
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            Integer colorInt = qpChannels.get(c).getColor();
            Color color = new Color(colorInt);

            // set the rendering settings with new min/max values
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            binding.setBlue(rtypes.rint(color.getBlue()));
            binding.setRed(rtypes.rint(color.getRed()));
            binding.setGreen(rtypes.rint(color.getGreen()));
            binding.setAlpha(rtypes.rint(color.getAlpha()));
        }

        // update the image on OMERO first
        boolean updateImageDisplay = OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), renderingSettings);

        // update the image thumbnail on OMERO
        boolean updateThumbnail = OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(), imageServer.getId(), renderingSettings.getId().getValue());

        return updateImageDisplay && updateThumbnail;
    }

    /**
     * Set the name the image on OMERO, based on QuPath settings.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image name on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendImageNameToOmero(OmeroRawImageServer imageServer){
        // get the image
        omero.gateway.model.ImageData image = OmeroRawTools.readOmeroImage(imageServer.getClient(), imageServer.getId());
        if(image != null) {
            image.setName(QPEx.getCurrentImageName());

            // update the image on OMERO first
            return OmeroRawTools.updateObjectOnOmero(imageServer.getClient(), image.asIObject());
        }

        return false;
    }


    /**
     * -----------------------------------------------------------------------------------------------------
     * Deprecated methods
     * -----------------------------------------------------------------------------------------------------
     */

    /**
     * Import ROIs from OMERO to QuPath and remove all current annotations/detections in QuPath.
     * See {@link #importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects)}
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     *
     * @return The list of OMERO rois converted into pathObjects.
     * @deprecated use {@link OmeroRawScripting#importOmeroROIsToQuPath(OmeroRawImageServer, boolean)} instead
     */
    @Deprecated
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer) {
        return importOmeroROIsToQuPath(imageServer, true);
    }

    /**
     * Read, from OMERO, tags attached to the current image.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return list of OMERO tags.
     */
    @Deprecated
    public static List<String> importOmeroTags(OmeroRawImageServer imageServer) {
        // read tags
        List<TagAnnotationData> omeroTagAnnotations = OmeroRawTools.readTags(imageServer.getClient(), imageServer.getId());

        // collect and convert to list
        return omeroTagAnnotations.stream().map(TagAnnotationData::getTagValue).collect(Collectors.toList());
    }

    /**
     * Send a collection of pathObjects to OMERO.
     *
     * <p>
     * <ul>
     * <li> Convert pathObjects to OMERO ROIs </li>
     * <li> Delete all current ROIs on OMERO if explicitly asked </li>
     * <li> Send ROIs to the current image on OMERO </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String)} instead.
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects, boolean deleteROIsOnOMERO) {
        return sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO, null);
    }


    /**
     * Send all QuPath detections to OMERO and delete existing ROIs is specified.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendDetectionsToOmero(OmeroRawImageServer, boolean, String)} instead.
     */
    @Deprecated
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        return sendDetectionsToOmero(imageServer, deleteROIsOnOMERO, null);
    }

    /**
     * Send all QuPath annotation objects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     *
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String)} instead.
     */
    @Deprecated
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        return sendAnnotationsToOmero(imageServer, deleteROIsOnOMERO, null);
    }

    /**
     * Send all QuPath objects (annotations and detections) to OMERO.
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendPathObjectsToOmero(OmeroRawImageServer, boolean, String)} instead.
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        return sendPathObjectsToOmero(imageServer, deleteROIsOnOMERO, null);
    }

}