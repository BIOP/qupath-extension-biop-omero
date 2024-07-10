package qupath.ext.biop.servers.omero.raw.utils;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.FileAnnotationWrapper;
import fr.igred.omero.annotations.GenericAnnotationWrapper;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.repository.ChannelWrapper;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PlateWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import fr.igred.omero.repository.ScreenWrapper;
import fr.igred.omero.repository.WellWrapper;
import fr.igred.omero.roi.ROIWrapper;
import javafx.collections.ObservableList;
import omero.ServerError;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.TablesFacility;
import omero.gateway.model.ChannelData;
import omero.gateway.model.DataObject;
import omero.gateway.model.DatasetData;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.TagAnnotationData;
import omero.gateway.model.WellData;
import omero.model.ChannelBinding;
import omero.model.IObject;
import omero.model.NamedValue;
import omero.model.RenderingDef;
import omero.rtypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.common.LogTools;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Static class which groups the methods of the scripting API for an OMERORawImageServer
 *
 * @author Rémy Dornier
 */
public class OmeroRawScripting {

    private static final String detectionFileBaseName = "QP detection table";
    private static final String annotationFileBaseName = "QP annotation table";
    private static final String summaryFileBaseName = "QP summary table";
    private static final String DEFAULT_KVP_NAMESPACE = "openmicroscopy.org/omero/client/mapAnnotation";
    private final static Logger logger = LoggerFactory.getLogger(OmeroRawScripting.class);
    private final static String FILE_NAME_SPLIT_REGEX = "_([\\d]*-[\\d]*h[\\d].m[\\d].*)";



    /**
     * get ROIs owned by the specified owner and linked to the current image from OMERO to QuPath
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param owner owner of the ROis to filter
     * @param qpNotif true to display a QuPath notification
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> getROIs(OmeroRawImageServer imageServer, String owner, boolean qpNotif) {
        List<ROIWrapper> roiWrappers;
        try{
            roiWrappers = imageServer.getImageWrapper().getROIs(imageServer.getClient().getSimpleClient());
        }catch(ServiceException | ExecutionException | AccessException e){
            Utils.errorLog(logger,"OMERO - ROIs", "Cannot get ROIs from image '"+imageServer.getId(), e, qpNotif);
            return Collections.emptyList();
        }

        if(roiWrappers.isEmpty())
            return new ArrayList<>();

        List<ROIWrapper> filteredROIs = OmeroRawShapes.filterByOwner(imageServer.getClient(), roiWrappers, owner);

        return OmeroRawShapes.createPathObjectsFromOmeroROIs(filteredROIs);
    }

    /**
     * get ROIs owned by the specified owner and linked to the current image from OMERO to QuPath
     *
     * @param client Omero client that handles the connection
     * @param imageId OMERO image ID
     * @param owner owner of the ROis to filter
     * @param qpNotif true to display a QuPath notification
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> getROIs(OmeroRawClient client, long imageId, String owner, boolean qpNotif) {
        List<ROIWrapper> roiWrappers;
        try{
            roiWrappers = client.getSimpleClient().getImage(imageId).getROIs(client.getSimpleClient());
        }catch(ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - ROIs", "Cannot get ROIs from image '"+imageId, e, qpNotif);
            return Collections.emptyList();
        }

        if(roiWrappers.isEmpty())
            return new ArrayList<>();

        List<ROIWrapper> filteredROIs = OmeroRawShapes.filterByOwner(client, roiWrappers, owner);

        return OmeroRawShapes.createPathObjectsFromOmeroROIs(filteredROIs);

    }

    /**
     * get and add to QuPath all ROIs owned by the specified owner and linked to the current image from OMERO to QuPath
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param owner owner of the ROis to filter
     * @param qpNotif true to display a QuPath notification
     *
     * @return The list of OMERO rois converted into pathObjects
     */
    public static Collection<PathObject> addROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects,
                                                 String owner, boolean qpNotif) {
        // read OMERO ROIs
        Collection<PathObject> pathObjects = getROIs(imageServer,  owner, qpNotif);

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
     * Send a collection of QuPath objects (annotations and/or detections) to OMERO, without deleting existing ROIs
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIs Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     *
     * @return the list of sent ROIWrappers or null if nothing can be sent to OMERO
     */
    public static List<ROIWrapper> sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIs, String owner, boolean qpNotif) {
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIs, owner, qpNotif);
    }


    /**
     * Send a collection of pathObjects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     *
     * @return the list of sent ROIWrappers or null if nothing can be sent to OMERO
     */
    public static List<ROIWrapper> sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects,
                                                 boolean deleteROIsOnOMERO, String owner, boolean qpNotif) {
        // convert pathObjects to OMERO ROIs
        List<ROIWrapper> omeroROIs = OmeroRawShapes.createOmeroROIsFromPathObjects(pathObjects);

        // get omero client and image id to send ROIs to the correct image
        OmeroRawClient client = imageServer.getClient();
        long imageId = imageServer.getId();

        // delete ROIs
        if (deleteROIsOnOMERO) {
            // get existing ROIs
            List<ROIWrapper> existingROIs;

            try {
                existingROIs = imageServer.getImageWrapper().getROIs(client.getSimpleClient());
            }catch(ServiceException | AccessException | ExecutionException e){
                Utils.errorLog(logger,"OMERO - ROIs", "Cannot get ROIs from image '"+imageId, e, qpNotif);
                return null;
            }

            // write new ROIs
            try {
                omeroROIs = imageServer.getImageWrapper().saveROIs(client.getSimpleClient(), omeroROIs);
            }catch(ServiceException | AccessException | ExecutionException e){
                Utils.errorLog(logger,"OMERO - ROIs", "Cannot add ROIs from image '"+imageId, e, qpNotif);
                return null;
            }

            // filter only owner's ROIs
            List<ROIWrapper> filteredROIs = OmeroRawShapes.filterByOwner(client, existingROIs, owner);

            // delete previous ROIs
            try {
                client.getSimpleClient().delete(filteredROIs);
            }catch(ServiceException | AccessException | ExecutionException | OMEROServerError | InterruptedException e){
                Utils.errorLog(logger,"OMERO - ROIs", "Cannot delete ROIs from image '"+imageId+"' for the owner '"+owner+"'", e, qpNotif);
            }

            return omeroROIs;
        } else {
            try {
                return imageServer.getImageWrapper().saveROIs(client.getSimpleClient(), omeroROIs);
            }catch(ServiceException | AccessException | ExecutionException e){
                Utils.errorLog(logger,"OMERO - ROIs", "Cannot add ROIs on image '"+imageId, e, qpNotif);
                return null;
            }
        }
    }


    /**
     * Send QuPath metadata to OMERO.
     * <p>
     * <ul>
     * <li> If the QuPath metadata key and value are identical, then a tag is created on OMERO </li>
     * <li> If the QuPath metadata key and value are different, then a key-value pair is created on OMERO </li>
     * </ul>
     * <p>
     * @param qpMetadata Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param kvpPolicy replacement policy you choose to replace key-value pairs on OMERO
     * @param tagPolicy replacement policy you choose to replace tags on OMERO
     * @param qpNotif true to display a QuPath notification
     *
     * @return a map containing the key-values / tags sent. Use {@link Utils#KVP_KEY} and {@link Utils#TAG_KEY} to access
     * the corresponding map. For tag, the returned map has identical key:value
     */
    // TODO wait for PR simple-omero-client for the return value of link method
    public static Map<String, Map<String, String>> sendQPMetadataToOmero(Map<String, String> qpMetadata, OmeroRawImageServer imageServer,
                                                Utils.UpdatePolicy kvpPolicy, Utils.UpdatePolicy tagPolicy, boolean qpNotif) {
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

        // initialize the map
        Map<String, Map<String, String>> resultsMap = new HashMap<>();
        resultsMap.put(Utils.KVP_KEY, new HashMap<>());
        resultsMap.put(Utils.TAG_KEY, new HashMap<>());

        if(!kvpPolicy.equals(Utils.UpdatePolicy.NO_UPDATE) && sendKeyValuesToOmero(qpMetadataKVP, imageServer, kvpPolicy, qpNotif))
            resultsMap.put(Utils.KVP_KEY, qpMetadataKVP);
        if(!tagPolicy.equals(Utils.UpdatePolicy.NO_UPDATE) && sendTagsToOmero(qpMetadataTags, imageServer, tagPolicy, qpNotif))
            resultsMap.put(Utils.TAG_KEY, qpMetadataTags.stream().collect(Collectors.toMap(e->e, e->e)));

        return resultsMap;
    }

    /**
     * Send a map to OMERO as Key-Value pairs. Check if OMERO keys are unique. If they are not, metadata are not sent
     *
     * @param qpMetadataKVP Map of key-value
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if key-value pairs have been sent ; false if there were troubles during the sending process)
     */
    // TODO wait for PR simple-omero-client for the return value of link method
    public static boolean sendKeyValuesToOmero(Map<String, String> qpMetadataKVP, OmeroRawImageServer imageServer, Utils.UpdatePolicy policy, boolean qpNotif){
        if(policy.equals(Utils.UpdatePolicy.NO_UPDATE)) {
            Utils.infoLog(logger,"OMERO - KVPs", "No metadata sent as KVPs and nothing updated on OMERO", qpNotif);
            return true;
        }

        // read OMERO key-values and check if they are unique
        List<MapAnnotationWrapper> omeroKVPsWrapperList;
        try {
            omeroKVPsWrapperList = imageServer.getImageWrapper().getMapAnnotations(imageServer.getClient().getSimpleClient());
        }catch(ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - KVPs", "Cannot get KVPs to the image '"+imageServer.getId()+"'", e, qpNotif);
            return false;
        }

        // check if OMERO keys are unique and store them in a map
        MapAnnotationWrapper flattenMapWrapper = Utils.flattenMapAnnotationWrapperList(omeroKVPsWrapperList);
        Map<String, String> omeroKVPs = new HashMap<>();
        try {
            omeroKVPs = Utils.convertMapAnnotationWrapperToMap(flattenMapWrapper);
        }catch(IllegalStateException e){
            if(policy.equals(Utils.UpdatePolicy.KEEP_KEYS) || policy.equals(Utils.UpdatePolicy.UPDATE_KEYS)){
                Utils.errorLog(logger,"OMERO - KVPs", "Keys not unique on OMERO. Please make them unique", qpNotif);
                return false;
            }
        }

        // convert key value pairs to omero-compatible object NamedValue
        List<NamedValue> newNV = new ArrayList<>();
        switch(policy){
            case UPDATE_KEYS :
                // split QuPath metadata into those that already exist on OMERO and those that need to be added
                Map<String,Map<String,String>> splitKeyValues = Utils.splitNewAndExistingKeyValues(omeroKVPs, qpMetadataKVP);
                Map<String,String>  newKV = splitKeyValues.get(Utils.NEW_KVP);
                Map<String, String> existingKV = splitKeyValues.get(Utils.EXISTING_KVP);

                for(String keyToUpdate : omeroKVPs.keySet()){
                    String valueToUpdate = omeroKVPs.get(keyToUpdate);
                    for (String updated : existingKV.keySet())
                        if (keyToUpdate.equals(updated))
                            omeroKVPs.replace(keyToUpdate, valueToUpdate, existingKV.get(keyToUpdate));
                }

                omeroKVPs.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
                newKV.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
                break;
            case DELETE_KEYS:
                qpMetadataKVP.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
                break;
            case KEEP_KEYS:
                // split QuPath metadata into those that already exist on OMERO and those that need to be added
                Map<String,Map<String,String>> splitKeyValuesList = Utils.splitNewAndExistingKeyValues(omeroKVPs, qpMetadataKVP);
                Map<String,String>  newKVList = splitKeyValuesList.get(Utils.NEW_KVP);
                newKVList.forEach((key,value)-> newNV.add(new NamedValue(key,value)));
        }

        if(!newNV.isEmpty()) {
            // set annotation map
            MapAnnotationWrapper newOmeroAnnotationMap = new MapAnnotationWrapper(newNV);
            newOmeroAnnotationMap.setNameSpace(DEFAULT_KVP_NAMESPACE);
            try{
                imageServer.getImageWrapper().link(imageServer.getClient().getSimpleClient(), newOmeroAnnotationMap);
            }catch(ServiceException | AccessException | ExecutionException e){
                Utils.errorLog(logger,"OMERO - KVPs", "Cannot add KVPs to the image '"+imageServer.getId()+"'", e, qpNotif);
                return false;
            }
        }else{
            Utils.warnLog(logger,"OMERO - KVPs", "No key values to send", qpNotif);
        }

        // delete current keyValues
        if(policy.equals(Utils.UpdatePolicy.DELETE_KEYS) || policy.equals(Utils.UpdatePolicy.UPDATE_KEYS)){
            try{
                imageServer.getClient().getSimpleClient().delete(omeroKVPsWrapperList);
            }catch(OMEROServerError | InterruptedException | ServiceException | AccessException | ExecutionException e){
                Utils.errorLog(logger,"OMERO - KVPs", "Cannot delete KVPs to the image '"+imageServer.getId()+"'", e, qpNotif);
            }
        }

        return true;
    }

    /**
     * Send a list of tags to OMERO. If tags are already attached to the image, these tags are not sent.
     *
     * @param tags List of tags to add to the image
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if tags have been sent ; false if there were troubles during the sending process)
     */
    // TODO wait for PR simple-omero-client for the return value of link method
    public static boolean sendTagsToOmero(List<String> tags, OmeroRawImageServer imageServer, Utils.UpdatePolicy policy, boolean qpNotif){
        if(policy.equals(Utils.UpdatePolicy.NO_UPDATE)) {
            Utils.infoLog(logger,"OMERO - tags", "No metadata sent as tags and nothing updated on OMERO", qpNotif);
            return true;
        }

        // unlink tags on OMERO
        if(policy.equals(Utils.UpdatePolicy.DELETE_KEYS)) {
            try {
                List<TagAnnotationWrapper> oldTags = imageServer.getImageWrapper().getTags(imageServer.getClient().getSimpleClient());
                imageServer.getImageWrapper().unlink(imageServer.getClient().getSimpleClient(), oldTags);
            }catch(ServiceException | AccessException | ExecutionException | OMEROServerError | InterruptedException e){
                Utils.errorLog(logger,"OMERO - tags", "Cannot unlink tags from the image '"+imageServer.getId()+"'", e, qpNotif);
            }
        }

        // get current OMERO tags
        List<TagAnnotationWrapper> omeroTagAnnotations;
        try {
            omeroTagAnnotations = imageServer.getImageWrapper().getTags(imageServer.getClient().getSimpleClient());
        }catch(ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - tags", "Cannot get tags to the image '"+imageServer.getId()+"'", e, qpNotif);
            return false;
        }

        List<String> currentTags = omeroTagAnnotations.stream().map(TagAnnotationWrapper::getName).collect(Collectors.toList());

        // remove all existing tags
        tags.removeAll(currentTags);

        if(tags.isEmpty()) {
            Dialogs.showInfoNotification("Sending tags", "All tags are already existing on OMERO.");
            return true;
        }

        List<TagAnnotationWrapper> tagsToAdd = new ArrayList<>();
        List<TagAnnotationWrapper> groupTags;
        try {
            groupTags= imageServer.getClient().getSimpleClient().getTags();
        }catch(ServiceException  | OMEROServerError e){
            Utils.errorLog(logger,"OMERO - tags",
                    "Cannot read tags from the current user '"+imageServer.getClient().getSimpleClient().getUser().getUserName()+"'", e, qpNotif);
            return false;
        }

        for(String tag:tags) {
            if(tagsToAdd.stream().noneMatch(e-> e.getName().equalsIgnoreCase(tag))){
                TagAnnotationWrapper newOmeroTagAnnotation;
                List<TagAnnotationWrapper> matchedTags = groupTags.stream().filter(e -> e.getName().equalsIgnoreCase(tag)).collect(Collectors.toList());
                if(matchedTags.isEmpty()){
                    newOmeroTagAnnotation = new TagAnnotationWrapper(new TagAnnotationData(tag));
                } else {
                    newOmeroTagAnnotation = matchedTags.get(0);
                }
                // find if the requested tag already exists
                tagsToAdd.add(newOmeroTagAnnotation);
            }
        }

        try {
            imageServer.getImageWrapper().link(imageServer.getClient().getSimpleClient(), tagsToAdd.toArray(TagAnnotationWrapper[]::new));
        }catch(ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - tags", "Cannot add tags to the image '"+imageServer.getId()+"'", e, qpNotif);
            return false;
        }

        return true;
    }


    /**
     * Read and add OMERO Key-Value pairs as QuPath metadata to the current image in the QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return a map of OMERO key-value pairs
     */
    public static Map<String, String> addKeyValuesToQuPath(OmeroRawImageServer imageServer, Utils.UpdatePolicy policy, boolean qpNotif) {
        // read OMERO key-values and check if they are unique
        List<MapAnnotationWrapper> omeroKVPsWrapperList;
        try {
            omeroKVPsWrapperList = imageServer.getImageWrapper().getMapAnnotations(imageServer.getClient().getSimpleClient());
        }catch(NullPointerException | ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - KVPs", "Cannot get KVPs from the image '"+imageServer.getId()+"'", e, qpNotif);
            return null;
        }

        // check if OMERO keys are unique and store them in a map
        MapAnnotationWrapper flattenMapWrapper = Utils.flattenMapAnnotationWrapperList(omeroKVPsWrapperList);
        Map<String, String> omeroKVPs;
        try {
            omeroKVPs = Utils.convertMapAnnotationWrapperToMap(flattenMapWrapper);
        }catch(IllegalStateException e){
            Utils.errorLog(logger,"OMERO - KVPs", "Keys not unique on OMERO. Please make them unique", qpNotif);
            return null;
        }

        if(omeroKVPs.isEmpty())
            return Collections.emptyMap();

        addKeyValuesToQuPath(QP.getProjectEntry(), omeroKVPs, policy, qpNotif);
        return omeroKVPs;
    }

    /**
     *
     * add Key-Value pairs as QuPath metadata to the image in the QuPath project.
     * <p>
     * WARNING : If you run {@link OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and / or {@link OmeroRawScripting#addParentHierarchyToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} before
     * {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and if you would like to use the policy {@link Utils.UpdatePolicy#DELETE_KEYS}, then you should apply this policy
     * to the first method but NOT to the second ones (use {@link Utils.UpdatePolicy#KEEP_KEYS} instead)
     *
     * @param entry the project entry to link the key-values to
     * @param kvps map containing the key-value to add
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     */
    public static void addKeyValuesToQuPath(ProjectImageEntry<BufferedImage> entry, Map<String, String> kvps, Utils.UpdatePolicy policy, boolean qpNotif) {
        // get project entry
        if(entry == null)
            entry = QP.getProjectEntry();

        // get qupath metadata
        Map<String, String> qpMetadata = entry.getMetadataMap();
        Map<String,String> newMetadata = new HashMap<>();

        switch(policy){
            case UPDATE_KEYS :
                // split key value pairs metadata into those that already exist in QuPath and those that need to be added
                Map<String, Map<String,String>> splitKeyValues = Utils.splitNewAndExistingKeyValues(qpMetadata, kvps);
                Map<String,String> newKV = splitKeyValues.get(Utils.NEW_KVP);
                Map<String,String> existingKV = splitKeyValues.get(Utils.EXISTING_KVP);
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
                newMetadata.putAll(newKV);
                newMetadata.putAll(updatedKV);

                // delete metadata
                entry.clearMetadata();
                break;
            case DELETE_KEYS:
                newMetadata.putAll(kvps);
                entry.clearMetadata();
                break;
            case KEEP_KEYS:
                // split QuPath metadata into those that already exist on OMERO and those that need to be added
                Map<String, Map<String,String>> splitKeyValues2 = Utils.splitNewAndExistingKeyValues(qpMetadata, kvps);
                Map<String,String> newKV2 = splitKeyValues2.get(Utils.NEW_KVP);
                newMetadata.putAll(newKV2);
            default: break;
        }

        newMetadata.forEach(entry::putMetadataValue);
    }

    /**
     * Read, from OMERO, tags attached to the current image and add them as QuPath metadata fields
     * <p>
     * WARNING : If you run {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and / or {@link OmeroRawScripting#addParentHierarchyToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} before
     * {@link OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and if you would like to use the policy {@link Utils.UpdatePolicy#DELETE_KEYS}, then you should apply this policy
     * to the first method but NOT to the second ones (use {@link Utils.UpdatePolicy#KEEP_KEYS} instead)
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return list of OMERO tags.
     */
    public static List<String> addTagsToQuPath(OmeroRawImageServer imageServer, Utils.UpdatePolicy policy, boolean qpNotif) {
        // read tags
        List<TagAnnotationWrapper> tagWrapperList;
        try {
            tagWrapperList =  imageServer.getImageWrapper().getTags(imageServer.getClient().getSimpleClient());
        }catch(ServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - TAGs", "Cannot get TAGs from the image '"+imageServer.getId()+"'", e, qpNotif);
            return null;
        }
        if(tagWrapperList.isEmpty())
            return Collections.emptyList();

        // collect and convert to list
        List<String> tagValues = tagWrapperList.stream().map(TagAnnotationWrapper::getName).collect(Collectors.toList());

        return addTagsToQuPath(QP.getProjectEntry(), tagValues, policy, qpNotif);
    }

    /**
     * Add a list of tags (string) as QuPath metadata fields
     * <p>
     * WARNING : If you run {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and / or {@link OmeroRawScripting#addParentHierarchyToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} before
     * {@link OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and if you would like to use the policy {@link Utils.UpdatePolicy#DELETE_KEYS}, then you should apply this policy
     * to the first method but NOT to the second ones (use {@link Utils.UpdatePolicy#KEEP_KEYS} instead)
     *
     * @param entry the project entry to link the tags to
     * @param tags List of strings
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return list of OMERO tags.
     */
    public static List<String> addTagsToQuPath(ProjectImageEntry<BufferedImage> entry, List<String> tags, Utils.UpdatePolicy policy, boolean qpNotif) {
        // create a map and add metadata
        Map<String,String> omeroTagMap =  tags.stream().collect(Collectors.toMap(e->e, e->e));
        addKeyValuesToQuPath(entry, omeroTagMap, policy, qpNotif);

        return tags;
    }

    /**
     * add the parent containers of the current image as QuPath metadata fields
     *
     * <p>
     * WARNING : If you run {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and / or {@link OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} before
     * {@link OmeroRawScripting#addParentHierarchyToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)}
     * and if you would like to use the policy {@link Utils.UpdatePolicy#DELETE_KEYS}, then you should apply this policy
     * to the first method but NOT to the second ones (use {@link Utils.UpdatePolicy#KEEP_KEYS} instead)
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param policy replacement policy you choose to replace annotations on OMERO
     * @param qpNotif true to display a QuPath notification
     * @return a map of the parent containers name % id
     */
    public static Map<String,String> addParentHierarchyToQuPath(OmeroRawImageServer imageServer, Utils.UpdatePolicy policy, boolean qpNotif){
        Map<String,String> containers;
        try{
            containers = OmeroRawTools.getParentHierarchy(imageServer, imageServer.getImageWrapper(), qpNotif);
        }catch(Exception e){
            Utils.errorLog(logger, "OMERO - parent",
                    "Cannot get the parent containers of image : "+imageServer.getImageWrapper().getName() +" : "+imageServer.getId(), e, qpNotif);
            return null;
        }

        if(containers.isEmpty())
            return Collections.emptyMap();

        // create a map and add metadata
        addKeyValuesToQuPath(QP.getProjectEntry(), containers, policy, qpNotif);

        return containers;
    }

    /**
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param imageData QuPath image
     * @param tableName Name of the OMERO.table
     * @param deleteOlderVersions Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new table ; -1 if sending failed
     */
    private static long sendMeasurementsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects,
                                                   ImageData<BufferedImage> imageData, String tableName,
                                                   boolean deleteOlderVersions, String owner, boolean qpNotif){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        OmeroRawClient client = imageServer.getClient();
        ImageWrapper imageWrapper = imageServer.getImageWrapper();

        // convert the table to OMERO.table
        TableWrapper tableWrapper = new TableWrapper(Utils.buildOmeroTableFromMeasurementTable(pathObjects, ob, imageWrapper));
        tableWrapper.setName(tableName);
        List<FileAnnotationWrapper> tableList = new ArrayList<>();

        if(deleteOlderVersions) {
            // get current tables
            try {
                tableList = client.getSimpleClient().getTablesFacility().getAvailableTables(client.getSimpleClient().getCtx(), imageWrapper.asDataObject())
                        .stream().map(FileAnnotationWrapper::new).collect(Collectors.toList());
            } catch (DSAccessException | ExecutionException | DSOutOfServiceException e) {
                Utils.errorLog(logger, "Sending Measurement to OMERO", "Cannot get the existing tables from image " + imageServer.getId(), e, qpNotif);
                deleteOlderVersions = false;
            }
        }
        // add the new table
        try{
            imageWrapper.addTable(client.getSimpleClient(), tableWrapper);
        }catch(DSAccessException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Sending Measurement to OMERO", "Cannot add the new table to the image "+imageServer.getId(), e, qpNotif);
            return -1L;
        }

        if(deleteOlderVersions) {
            // find the name of the table to delete
            String[] groups = tableName.split(FILE_NAME_SPLIT_REGEX);
            String matchedTableName;
            if (groups.length == 0) {
                matchedTableName = tableName.substring(0, tableName.lastIndexOf("_"));
            } else {
                matchedTableName = groups[0];
            }
            try {
                deletePreviousFileVersions(client, tableList, matchedTableName, TablesFacility.TABLES_MIMETYPE, owner);
            }catch(ServiceException | OMEROServerError | ExecutionException | InterruptedException | AccessException e){
                Utils.errorLog(logger, "Sending Measurement to OMERO", "Cannot delete previous tables from image "+imageServer.getId(), e, qpNotif);
            }
        }
        return tableWrapper.getId();
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param annotationObjects QuPath annotations objects
     * @param imageData QuPath image
     * @param deleteOlderVersions Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new table ; -1 if sending failed
     */
    public static long sendAnnotationMeasurementsToOmero(OmeroRawImageServer imageServer,
                                                         Collection<PathObject> annotationObjects,
                                                         ImageData<BufferedImage> imageData, boolean deleteOlderVersions,
                                                         String owner, boolean qpNotif){
        // set the table name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();
        return sendMeasurementsToOmero(imageServer, annotationObjects, imageData, name, deleteOlderVersions, owner, qpNotif);
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table with a default table name referring to detections
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param detectionObjects QuPath annotations or detections objects
     * @param imageData QuPath image
     * @param deletePrevious Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new table ; -1 if sending failed
     */
    public static long sendDetectionMeasurementsToOmero(OmeroRawImageServer imageServer,
                                                        Collection<PathObject> detectionObjects,
                                                        ImageData<BufferedImage> imageData, boolean deletePrevious,
                                                        String owner, boolean qpNotif){
        // set the table name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();
        return sendMeasurementsToOmero(imageServer, detectionObjects, imageData, name, deletePrevious, owner, qpNotif);
    }

    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param annotationObjects QuPath annotations objects
     * @param imageData QuPath image
     * @param deletePrevious Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new CSV file ; -1 if sending failed
     */
    public static long sendAnnotationMeasurementsAsCSVToOmero(OmeroRawImageServer imageServer,
                                                                 Collection<PathObject> annotationObjects,
                                                                 ImageData<BufferedImage> imageData, boolean deletePrevious,
                                                                 String owner, boolean qpNotif){
        // set the file name
        String name = annotationFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();
        return sendMeasurementAsCSVToOmero(imageServer, annotationObjects, imageData, name, deletePrevious, owner, qpNotif);
    }

    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param detectionObjects QuPath annotations objects
     * @param imageData QuPath image
     * @param deletePrevious Delete of not all previous OMERO measurement tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new CSV file ; -1 if sending failed
     */
    public static long sendDetectionMeasurementsAsCSVToOmero(OmeroRawImageServer imageServer,
                                                                 Collection<PathObject> detectionObjects,
                                                                 ImageData<BufferedImage> imageData, boolean deletePrevious,
                                                                 String owner, boolean qpNotif){
        // set the file name
        String name = detectionFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();
        return sendMeasurementAsCSVToOmero(imageServer, detectionObjects, imageData, name, deletePrevious, owner, qpNotif);
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file
     *
     * @param pathObjects QuPath annotations or detections objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the CSV file
     * @param deleteOlderVersions Delete or not all previous versions of csv measurements tables
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new CSV file ; -1 if sending failed
     */
    private static long sendMeasurementAsCSVToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects,
                                                       ImageData<BufferedImage> imageData, String filename,
                                                       boolean deleteOlderVersions, String owner, boolean qpNotif){
        // get the measurement table
        ObservableMeasurementTableData ob = new ObservableMeasurementTableData();
        ob.setImageData(imageData, pathObjects);

        // build the csv file from the measurement table
        File file = Utils.buildCSVFileFromMeasurementTable(pathObjects, ob, imageServer.getId(), filename);
        long fileId = -1L;

        if (file.exists()) {
            OmeroRawClient client = imageServer.getClient();
            ImageWrapper imageWrapper = imageServer.getImageWrapper();
            List<FileAnnotationWrapper> attachments = new ArrayList<>();

            if (deleteOlderVersions) {
                try {
                    attachments = imageServer.getImageWrapper().getFileAnnotations(client.getSimpleClient());
                } catch (DSAccessException | ExecutionException | DSOutOfServiceException e) {
                    Utils.errorLog(logger, "Sending Measurement as CSV to OMERO", "Cannot get the existing files from image " + imageServer.getId(), e, qpNotif);
                    deleteOlderVersions = false;
                }
            }

            try{
                fileId = imageWrapper.addFile(client.getSimpleClient(), file);
            }catch(InterruptedException | ExecutionException e){
                Utils.errorLog(logger, "Sending Measurement as CSV to OMERO", "Cannot add the new CSV file to the image "+imageServer.getId(), e, qpNotif);
                return -1L;
            }

            if(deleteOlderVersions) {
                String[] groups = filename.split(FILE_NAME_SPLIT_REGEX);
                String matchedFileName;
                if (groups.length == 0)
                    matchedFileName = filename.substring(0, filename.lastIndexOf("_"));
                else matchedFileName = groups[0];

                try {
                    deletePreviousFileVersions(client, attachments, matchedFileName, FileAnnotationData.MS_EXCEL, owner);
                }catch(ServiceException | OMEROServerError | ExecutionException | InterruptedException | AccessException e){
                    Utils.errorLog(logger, "Sending Measurement as CSV to OMERO", "Cannot delete previous files from the image "+imageServer.getId(), e, qpNotif);
                }
            }
            // delete the temporary file
            file.delete();
        }
        return fileId;
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
        Utils.buildListsOfStringsFromMeasurementTable(parentTable, ob, pathObjects, imageServer.getId());
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
     * @param deleteOlderVersions True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new csv file ; -1 if sending failed
     */
    public static long sendParentMeasurementsAsCSVToOmero(LinkedHashMap<String, List<String>> parentTable,
                                                          OmeroRawClient client, Collection<GenericRepositoryObjectWrapper<?>> parents,
                                                          boolean deleteOlderVersions, String owner, boolean qpNotif){
        // set the file name
        String filename = summaryFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();

        // build the CSV parent table
        File parentCSVFile = Utils.buildCSVFileFromListsOfStrings(parentTable, filename);

        FileAnnotationWrapper attachedFileWrapper = null;
        if (parentCSVFile.exists()) {
            // create an annotation file
            attachedFileWrapper = new FileAnnotationWrapper(new FileAnnotationData(parentCSVFile));

            // loop over all parents if images comes from more than one dataset
            for(GenericRepositoryObjectWrapper<?> parent : parents) {
                List<FileAnnotationWrapper> attachments = new ArrayList<>();
                if (deleteOlderVersions) {
                    try {
                        attachments = parent.getFileAnnotations(client.getSimpleClient());
                    } catch (DSAccessException | ExecutionException | DSOutOfServiceException e) {
                        Utils.errorLog(logger, "Sending Measurement to parent as CSV ",
                                "Cannot get the existing files from " + parent.getClass().getSimpleName() + ":" + parent.getId(), e, qpNotif);
                        deleteOlderVersions = false;
                    }
                }
                try{
                    // link the file if it has already been uploaded once. Upload it otherwise
                    if (attachedFileWrapper.getId() > 0)
                        parent.linkIfNotLinked(client.getSimpleClient(), attachedFileWrapper);
                    else {
                        long fileId = parent.addFile(client.getSimpleClient(), parentCSVFile);
                        Optional<FileAnnotationWrapper> optParentFile = parent.getFileAnnotations(client.getSimpleClient())
                                .stream()
                                .filter(e->e.getId() == fileId)
                                .findFirst();

                        if(optParentFile.isPresent())
                            attachedFileWrapper = optParentFile.get();
                        else Utils.warnLog(logger, "Sending measurements to parent as CSV",
                                "The newly added parent csv file cannot be retrieved from OMERO", qpNotif);
                    }
                }catch(DSAccessException | InterruptedException | ServiceException | ExecutionException e){
                    Utils.errorLog(logger, "Sending measurements to parent as CSV",
                            "Cannot add the new table to the "+parent.getClass().getSimpleName()+":"+parent.getId(), e, qpNotif);
                    return -1L;
                }

                // delete previous files
                if (deleteOlderVersions) {
                    String[] groups = filename.split(FILE_NAME_SPLIT_REGEX);
                    String matchedFileName;
                    if (groups.length == 0)
                        matchedFileName = filename.substring(0, filename.lastIndexOf("_"));
                    else matchedFileName = groups[0];

                    try{
                        deletePreviousFileVersions(client, attachments, matchedFileName, FileAnnotationData.MS_EXCEL, owner);
                    }catch(ServiceException | OMEROServerError | ExecutionException | InterruptedException | AccessException e){
                        Utils.errorLog(logger, "Send measurements to parent as CSV",
                                "Cannot delete previous tables from image "+parent.getClass().getSimpleName()+":"+parent.getId(), e, qpNotif);
                    }
                }
            }
            // delete the temporary file
            parentCSVFile.delete();
        }
        return (attachedFileWrapper == null ? -1L : attachedFileWrapper.getId());
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
     * @param deleteOlderVersions True if you want to delete previous CSV files on the parent container, linked to the current QuPath project
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     * @return the ID of the new table ; -1 if sending failed
     */
    public static long sendParentMeasurementsToOmero(LinkedHashMap<String, List<String>> parentTable,
                                                                 OmeroRawClient client, Collection<GenericRepositoryObjectWrapper<?>> parents,
                                                                 boolean deleteOlderVersions, String owner, boolean qpNotif){
        // set the file name
        String tableName = summaryFileBaseName + "_" +
                QPEx.getQuPath().getProject().getName().split("/")[0] + "_"+
                Utils.getCurrentDateAndHour();

        // build the OMERO.table parent table
        TableWrapper omeroTable = new TableWrapper(Utils.buildOmeroTableFromListsOfStrings(parentTable, client));
        omeroTable.setName(tableName);
        FileAnnotationWrapper attachedFileWrapper = null;

        // loop over all parents if images comes from more than one dataset
        for(GenericRepositoryObjectWrapper<?> parent : parents) {
            Collection<FileAnnotationWrapper> tableList = new ArrayList<>();
            if (deleteOlderVersions) {
                // get all attachments before adding new ones
                try {
                    tableList = client.getSimpleClient().getTablesFacility().getAvailableTables(client.getSimpleClient().getCtx(), parent.asDataObject())
                            .stream().map(FileAnnotationWrapper::new).collect(Collectors.toList());
                } catch (DSAccessException | ExecutionException | DSOutOfServiceException e) {
                    Utils.errorLog(logger, "Send measurements to parent",
                            "Cannot get the existing files from " + parent.getClass().getSimpleName() + ":" + parent.getId(), e, qpNotif);
                    deleteOlderVersions = false;
                }
            }

            try{
                // link the file if it has already been uploaded once. Upload it otherwise
                if (attachedFileWrapper != null && attachedFileWrapper.getId() > 0)
                    parent.linkIfNotLinked(client.getSimpleClient(), attachedFileWrapper);
                else {
                    // add the new table
                    parent.addTable(client.getSimpleClient(), omeroTable);
                    Optional<FileAnnotationWrapper> optParentTable = client.getSimpleClient().getTablesFacility()
                            .getAvailableTables(client.getSimpleClient().getCtx(), parent.asDataObject())
                            .stream()
                            .map(FileAnnotationWrapper::new)
                            .filter(e -> e.getId() == omeroTable.getId())
                            .findFirst();
                    if(optParentTable.isPresent())
                        attachedFileWrapper = optParentTable.get();
                    else Utils.warnLog(logger, "Send measurements to parent",
                            "The newly added parent table cannot be retrieved from OMERO", qpNotif);
                }
            }catch(DSAccessException | DSOutOfServiceException | ExecutionException e){
                Utils.errorLog(logger, "Sending measurement to parent",
                        "Cannot add the new table to the "+parent.getClass().getSimpleName()+":"+parent.getId(), e, qpNotif);
                return -1L;
            }

            // delete previous files
            if (deleteOlderVersions && attachedFileWrapper != null) {
                String[] groups = tableName.split(FILE_NAME_SPLIT_REGEX);
                String matchedFileName;
                if(groups.length == 0)
                    matchedFileName = tableName.substring(0, tableName.lastIndexOf("_"));
                else matchedFileName = groups[0];

                try{
                    deletePreviousFileVersions(client, tableList, matchedFileName, TablesFacility.TABLES_MIMETYPE, owner);
                }catch(ServiceException | OMEROServerError | ExecutionException | InterruptedException | AccessException e){
                    Utils.errorLog(logger, "Send measurements to parent",
                            "Cannot delete previous tables from image "+parent.getClass().getSimpleName()+":"+parent.getId(), e, qpNotif);
                }
            }
        }
        return omeroTable.getId();
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     */
    public static boolean deleteAnnotationMeasurements(OmeroRawImageServer imageServer, String owner, boolean qpNotif){
        try{
            List<FileAnnotationWrapper> fileWrappers = imageServer.getImageWrapper().getFileAnnotations(imageServer.getClient().getSimpleClient());
            String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, FileAnnotationData.MS_EXCEL, owner);
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, TablesFacility.TABLES_MIMETYPE, owner);
            return true;
        }catch(OMEROServerError | DSAccessException | InterruptedException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Delete detection measurements", "Cannot delete the project's detection measurements from image "+imageServer.getId(), e, qpNotif);
            return false;
        }
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @param qpNotif true to display a QuPath notification
     */
    public static boolean deleteDetectionMeasurements(OmeroRawImageServer imageServer, String owner, boolean qpNotif){
        try{
            List<FileAnnotationWrapper> fileWrappers = imageServer.getImageWrapper().getFileAnnotations(imageServer.getClient().getSimpleClient());
            String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, FileAnnotationData.MS_EXCEL, owner);
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, TablesFacility.TABLES_MIMETYPE, owner);
            return true;
        }catch(OMEROServerError | DSAccessException | InterruptedException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Delete detection measurements", "Cannot delete the project's detection measurements from image "+imageServer.getId(), e, qpNotif);
            return false;
        }
    }


    /**
     * Delete all previous version of tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the given table name in the list of files.
     *
     * @param client Omero client
     * @param fileWrappers List of files to browse
     * @param name Table name that files name must contain to be deleted (i.e. filtering item)
     * @param format file format to look for
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     */
    private static void deletePreviousFileVersions(OmeroRawClient client, Collection<FileAnnotationWrapper> fileWrappers, String name, String format, String owner)
            throws ServiceException, OMEROServerError, AccessException, ExecutionException, InterruptedException {
        if(!fileWrappers.isEmpty()) {
            List<FileAnnotationWrapper> previousTables = fileWrappers.stream()
                    .filter(e -> e.getFileName().contains(name) &&
                            (format == null || format.isEmpty() || e.getFileFormat().equals(format) || e.getOriginalMimetype().equals(format)))
                    .collect(Collectors.toList());

            List<FileAnnotationWrapper> filteredTables = new ArrayList<>();
            Map<Long, String> ownerMap = new HashMap<>();

            if(owner != null && !owner.isEmpty() && !owner.equals(Utils.ALL_USERS)) {
                for (FileAnnotationWrapper previousTable : previousTables) {
                    // get the ROI's owner ID
                    long ownerId = previousTable.getOwner().getId();
                    String tableOwner;

                    // get the ROI's owner
                    if (ownerMap.containsKey(ownerId)) {
                        tableOwner = ownerMap.get(ownerId);
                    } else {
                        ExperimenterWrapper ownerObj = client.getSimpleClient().getUser(ownerId);
                        tableOwner = ownerObj.getUserName();
                        ownerMap.put(ownerId, tableOwner);
                    }

                    if (tableOwner.equals(owner))
                        filteredTables.add(previousTable);
                }
            }else{
                filteredTables = previousTables;
            }

            if (!filteredTables.isEmpty())
                client.getSimpleClient().delete(filteredTables);
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
     * @param imageData ImageData QuPath object of the current image
     * @param qpNotif true to display a QuPath notification
     */
    public static void copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, boolean qpNotif) {
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings;
        try {
            renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getImageWrapper().getPixels().getId());
        }catch(DSOutOfServiceException | ServerError e){
            Utils.errorLog(logger,"OMERO - Channels contrast", "Cannot access to rendering settings of the image " + imageServer.getId(), e, qpNotif);
            return;
        }

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Utils.errorLog(logger,"OMERO - Channels contrast", "Cannot read to rendering settings of the image " + imageServer.getId(), qpNotif);
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels;
        try {
            omeroNChannels = imageServer.getImageWrapper().getChannels(imageServer.getClient().getSimpleClient()).size();
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels contrast", "Cannot read channels on image "+imageServer.getId(), qpNotif);
            return;
        }

        // check if both images has the same number of channels
        if(omeroNChannels != imageServer.nChannels()){
            Utils.warnLog(logger, "OMERO - Channels contrast",
                    "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")", qpNotif);
            return;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get the min-max per channel from OMERO
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            double minDynamicRange = binding.getInputStart().getValue();
            double maxDynamicRange = binding.getInputEnd().getValue();

            // set the dynamic range
            QPEx.setChannelDisplayRange(imageData, c, minDynamicRange, maxDynamicRange);
        }

        // Update the thumbnail
        try{
            updateQuPathThumbnail();
        }catch (IOException e){
            Utils.errorLog(logger,"QuPath - Update Thumbnail", "Cannot update QuPath thumbnail ", e, qpNotif);
        }
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
     * @param imageData ImageData QuPath object of the current image
     * @param qpNotif true to display a QuPath notification
     */
    public static void copyOmeroChannelsColorToQuPath(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, boolean qpNotif){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings;
        try {
            renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getImageWrapper().getPixels().getId());
        }catch(DSOutOfServiceException | ServerError e){
            Utils.errorLog(logger,"OMERO - Channels color", "Cannot access to rendering settings of the image " + imageServer.getId(), e, qpNotif);
            return;
        }

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Utils.errorLog(logger,"OMERO - Channels color", "Cannot read to rendering settings of the image " + imageServer.getId(), qpNotif);
            return;
        }

        // get the number of the channels in OMERO
        int omeroNChannels;
        try {
            omeroNChannels = imageServer.getImageWrapper().getChannels(imageServer.getClient().getSimpleClient()).size();
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels color", "Cannot read channels on image "+imageServer.getId(), qpNotif);
            return;
        }

        // check if both images has the same number of channels
        if(omeroNChannels != imageServer.nChannels()){
            Utils.warnLog(logger, "OMERO - Channels color",
                    "The image on OMERO has not the same number of channels ("+omeroNChannels+" as the one in QuPath ("+imageServer.nChannels()+")", qpNotif);
            return;
        }

        List<Integer> colors = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            // get OMERO channels color
            colors.add(new Color(binding.getRed().getValue(),binding.getGreen().getValue(), binding.getBlue().getValue(), binding.getAlpha().getValue()).getRGB());
        }

        // set QuPath channels color
        QPEx.setChannelColors(imageData, colors.toArray(new Integer[0]));

        // Update the thumbnail
        try{
            updateQuPathThumbnail();
        }catch (IOException e){
            Utils.errorLog(logger,"QuPath - Update Thumbnail", "Cannot update QuPath thumbnail ", e, qpNotif);
        }
    }

    /**
     * Update QuPath thumbnail
     */
    private static void updateQuPathThumbnail() throws IOException {
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
     * @param imageData ImageData QuPath object of the current image
     * @param qpNotif true to display a QuPath notification
     */
    public static void copyOmeroChannelsNameToQuPath(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData, boolean qpNotif){
        // get the number of the channels in OMERO
        List<ChannelWrapper> omeroChannels;
        try {
            omeroChannels = imageServer.getImageWrapper().getChannels(imageServer.getClient().getSimpleClient());
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels Name", "Cannot read channels on image "+imageServer.getId(), e, qpNotif);
            return;
        }

        // check if both images has the same number of channels
        if(omeroChannels.size() != imageServer.nChannels()){
           Utils.warnLog(logger, "OMERO - Channels Name",
                   "The image on OMERO has not the same number of channels ("+omeroChannels.size()+" as the one in QuPath ("+imageServer.nChannels()+")", qpNotif);
            return;
        }

        List<String> names = new ArrayList<>();

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get OMERO channels name
            names.add(omeroChannels.get(c).getName());
        }

        // set QuPath channels name
        QPEx.setChannelNames(imageData, names.toArray(new String[0]));
    }

    /**
     * Set the minimum and maximum display range value of each channel on OMERO, based on QuPath settings.<br>
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images and NOT batchable </li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendQuPathChannelsDisplayRangeToOmero(OmeroRawImageServer imageServer, boolean qpNotif){
        // get current channels from QuPath
        ObservableList<ChannelDisplayInfo> qpChannels = QPEx.getQuPath().getViewer().getImageDisplay().availableChannels();

        int nChannels = imageServer.nChannels();
        double[] min = new double[nChannels];
        double[] max = new double[nChannels];

        for(int c = 0; c < nChannels; c++) {
            // get min/max display
            min[c] = qpChannels.get(c).getMinDisplay();
            max[c] = qpChannels.get(c).getMaxDisplay();
        }

        return sendChannelsDisplayRangeToOmero(imageServer, min, max, qpNotif);
    }

    /**
     * Set a minimum and maximum display range value of each channel on OMERO.<br>
     * OMERO image and thumbnail are updated accordingly. <br>
     * Channel indices are taken as reference.
     * <p>
     * <ul>
     * <li> Only works for fluorescence images</li>
     * </ul>
     * <p>
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param min Array of size nChannels with the minDisplayRange for each channel
     * @param max Array of size nChannels with the maxDisplayRange for each channel
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendChannelsDisplayRangeToOmero(OmeroRawImageServer imageServer, double[] min, double[] max, boolean qpNotif){
        // get the number of channel of the current image
        int nChannels = imageServer.nChannels();

        // check any channel size mismatch
        if(min.length != max.length || min.length != nChannels){
            Utils.errorLog(logger,"OMERO - Channels contrast", "The number of channels of the current image ("+nChannels+") is not equal to " +
                    "the number of display range you want to send ("+min.length+" & "+max.length+")." +
                    "Please make them equal", qpNotif);
            return false;
        }

        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings;
        try {
            renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getImageWrapper().getPixels().getId());
        }catch(DSOutOfServiceException | ServerError e){
            Utils.errorLog(logger,"OMERO - Channels", "Cannot access to rendering settings of the image " + imageServer.getId(), e, qpNotif);
            return false;
        }

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Utils.errorLog(logger,"OMERO - Channels", "Cannot read to rendering settings of the image " + imageServer.getId(), qpNotif);
            return false;
        }

        int omeroNChannels;
        Client simpleClient = imageServer.getClient().getSimpleClient();
        try {
            omeroNChannels = imageServer.getImageWrapper().getChannels(simpleClient).size();
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels", "Cannot read channels on image "+imageServer.getId(), true);
            return false;
        }

        // check if both images has the same number of channels
        if(omeroNChannels != nChannels){
            Utils.warnLog(logger, "OMERO - Channels",
                    "The image on QuPath has not the same number of channels ("+nChannels+" as the one in OMERO ("+omeroNChannels+")", qpNotif);
            return false;
        }

        for(int c = 0; c < nChannels; c++) {
            if(!Double.isNaN(min[c]) && !Double.isNaN(max[c])) {
                // set the rendering settings with new min/max values
                ChannelBinding binding = renderingSettings.getChannelBinding(c);
                binding.setInputStart(rtypes.rdouble(min[c]));
                binding.setInputEnd(rtypes.rdouble(max[c]));
            }
        }

        // update the image on OMERO first
        boolean successfulUpdate =  true;
        try {
            simpleClient.getDm().updateObject(simpleClient.getCtx(), renderingSettings, null);
        }catch(DSOutOfServiceException | DSAccessException | ExecutionException e ){
            successfulUpdate = false;
            Utils.errorLog(logger,"OMERO - Channels contrast", "Cannot update image "+imageServer.getId() +" on OMERO", e, qpNotif);
        }

        // update the image thumbnail on OMERO
        successfulUpdate = successfulUpdate && OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(), imageServer.getId(), renderingSettings.getId().getValue());

        return successfulUpdate;

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
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendQuPathChannelsNameToOmero(OmeroRawImageServer imageServer, boolean qpNotif){
        // get the number of the channels in OMERO
        List<ChannelWrapper> omeroChannels;
        Client simpleClient = imageServer.getClient().getSimpleClient();
        try {
            omeroChannels = imageServer.getImageWrapper().getChannels(simpleClient);
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels", "Cannot read channels on image "+imageServer.getId(), e, qpNotif);
            return false;
        }

        // check if both images has the same number of channels
        if(omeroChannels.size() != imageServer.nChannels()){ // can use imageServer.nChannels() to get the real number of channel
            Utils.warnLog(logger,"OMERO - Channels",
                    "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroChannels.size()+")", qpNotif);
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            String qpChName = imageServer.getChannel(c).getName();

            // set the rendering settings with new min/max values
            omeroChannels.get(c).setName(qpChName);
        }

        List<IObject> channelIObjects = omeroChannels.stream().map(ChannelWrapper::asDataObject).map(ChannelData::asIObject).collect(Collectors.toList());

        // update channel names
        boolean successfulUpdate =  true;
        try {
            simpleClient.getDm().updateObjects(simpleClient.getCtx(), channelIObjects, null);
        }catch(DSOutOfServiceException | DSAccessException | ExecutionException e ){
            successfulUpdate = false;
            Utils.errorLog(logger,"OMERO - Channels Name", "Cannot update image "+imageServer.getId() +" on OMERO", e, qpNotif);
        }
        return successfulUpdate;
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
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if the image and thumbnail on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendQuPathChannelsColorToOmero(OmeroRawImageServer imageServer, boolean qpNotif){
        // get the OMERO rendering settings to get channel info
        RenderingDef renderingSettings;
        try {
            renderingSettings = OmeroRawTools.readOmeroRenderingSettings(imageServer.getClient(), imageServer.getImageWrapper().getPixels().getId());
        }catch(DSOutOfServiceException | ServerError e){
            Utils.errorLog(logger,"OMERO - Channels", "Cannot access to rendering settings of the image " + imageServer.getId(),e,qpNotif);
            return false;
        }

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Utils.errorLog(logger,"OMERO - Channels", "Cannot read to rendering settings of the image " + imageServer.getId(), qpNotif);
            return false;
        }

        int omeroNChannels;
        Client simpleClient = imageServer.getClient().getSimpleClient();
        try {
            omeroNChannels = imageServer.getImageWrapper().getChannels(simpleClient).size();
        }catch(AccessException | ServiceException | ExecutionException e){
            Utils.errorLog(logger,"OMERO - Channels", "Cannot read channels on image "+imageServer.getId(), e, qpNotif);
            return false;
        }

        // check if both images has the same number of channels
        if(omeroNChannels != imageServer.nChannels()){ // can use imageServer.nChannels() to get the real number of channel
            Utils.warnLog(logger, "OMERO - Channels",
                    "The image on QuPath has not the same number of channels ("+imageServer.nChannels()+" as the one in OMERO ("+omeroNChannels+")", qpNotif);
            return false;
        }

        for(int c = 0; c < imageServer.nChannels(); c++) {
            // get min/max display
            Integer colorInt = imageServer.getChannel(c).getColor();
            Color color = new Color(colorInt);

            // set the rendering settings with new min/max values
            ChannelBinding binding = renderingSettings.getChannelBinding(c);
            binding.setBlue(rtypes.rint(color.getBlue()));
            binding.setRed(rtypes.rint(color.getRed()));
            binding.setGreen(rtypes.rint(color.getGreen()));
            binding.setAlpha(rtypes.rint(color.getAlpha()));
        }

        // update the image on OMERO first
        boolean successfulUpdate =  true;
        try {
            simpleClient.getDm().updateObject(simpleClient.getCtx(), renderingSettings, null);
        }catch(DSOutOfServiceException | DSAccessException | ExecutionException e ){
            successfulUpdate = false;
            Utils.errorLog(logger,"OMERO - Channels Color", "Cannot update image "+imageServer.getId() +" on OMERO", e, qpNotif);
        }

        // update the image thumbnail on OMERO
        successfulUpdate = successfulUpdate && OmeroRawTools.updateOmeroThumbnail(imageServer.getClient(), imageServer.getId(), renderingSettings.getId().getValue());

        return successfulUpdate;
    }


    /**
     * Set the name the image on OMERO, based on QuPath settings.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param qpNotif true to display a QuPath notification
     * @return Sending status (true if the image name on OMERO has been updated ; false if there were troubles during the sending process)
     */
    public static boolean sendQuPathImageNameToOmero(OmeroRawImageServer imageServer, boolean qpNotif){
        // get the image
        ImageWrapper image = imageServer.getImageWrapper();
        if(image != null) {
            image.setName(imageServer.getMetadata().getName());

            // update the image on OMERO
            boolean successfulUpdate =  true;
            Client simpleClient = imageServer.getClient().getSimpleClient();
            try {
                simpleClient.getDm().updateObject(simpleClient.getCtx(), image.asDataObject().asIObject(), null);
            }catch(DSOutOfServiceException | DSAccessException | ExecutionException e ){
                successfulUpdate = false;
                Utils.errorLog(logger,"OMERO - Image name", "Cannot update image "+imageServer.getId() +" on OMERO", e, qpNotif);
            }
            return successfulUpdate;
        }
        return false;
    }


    /*
     *
     *
     *                                           Deprecated methods
     *
     *
     */

    /**
     * Import all ROIs, whatever the owner, from OMERO to QuPath, for the current image and remove all current annotations/detections in QuPath.
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     *
     * @return The list of OMERO rois converted into pathObjects.
     * @deprecated use {@link OmeroRawScripting#addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "importOmeroROIsToQuPath(OmeroRawImageServer) is deprecated - " +
                "use addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean) instead");
        return addROIsToQuPath(imageServer,  true, Utils.ALL_USERS, true);
    }

    /**
     * Read, from OMERO, tags attached to the current image.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return list of OMERO tags.
     * @deprecated  use {@link OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static List<String> importOmeroTags(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "importOmeroTags(OmeroRawImageServer) is deprecated - " +
                "use addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");

        // read tags
        List<TagAnnotationWrapper> omeroTagAnnotations;
        try {
            omeroTagAnnotations = imageServer.getImageWrapper().getTags(imageServer.getClient().getSimpleClient());
        }catch(ServiceException | AccessException | ExecutionException e){
            String message = "Cannot read tags from image '"+imageServer.getId()+"'";
            String header = "OMERO - tags";
            Dialogs.showErrorNotification(header, message);
            logger.error(header + "---" + message + "\n" + e + "\n"+ Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
        // collect and convert to list
        return omeroTagAnnotations.stream().map(TagAnnotationWrapper::getName).collect(Collectors.toList());
    }

    /**
     * Send a collection of pathObjects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean)} instead.
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects, boolean deleteROIsOnOMERO) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean) is deprecated - " +
                "use sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean) instead");

        List<ROIWrapper> rois = sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO, Utils.ALL_USERS, true);
        return rois != null && !rois.isEmpty();
    }


    /**
     * Send all QuPath detections to OMERO and delete existing ROIs is specified.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated method removed
     */
    @Deprecated
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        LogTools.warnOnce(logger, "sendDetectionsToOmero(OmeroRawImageServer, boolean) is deprecated - " +
                "will not be replaced");
        return sendDetectionsToOmero(imageServer, deleteROIsOnOMERO, Utils.ALL_USERS);
    }

    /**
     * Send all QuPath objects (annotations and detections) to OMERO and deletes ROIs already stored on OMERO
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
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
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer, boolean, String) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
        Collection<PathObject> pathObjects = QP.getAnnotationObjects();
        pathObjects.addAll(QP.getDetectionObjects());
        return sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO, owner);
    }

    /**
     * Send all QuPath annotation objects to OMERO, without deleting current ROIs on OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendAnnotationsToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
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
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        LogTools.warnOnce(logger, "sendAnnotationsToOmero(OmeroRawImageServer, boolean, String) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
        Collection<PathObject> annotations = QP.getAnnotationObjects();
        return sendPathObjectsToOmero(imageServer, annotations, deleteROIsOnOMERO, owner);
    }


    /**
     * Send all QuPath detection objects to OMERO without deleting existing ROIs.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated method removed
     */
    @Deprecated
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendDetectionsToOmero(OmeroRawImageServer) is deprecated - " +
                "will not be replaced");
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
     * @deprecated method removed
     */
    @Deprecated
    public static boolean sendDetectionsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO, String owner) {
        LogTools.warnOnce(logger, "sendDetectionsToOmero(OmeroRawImageServer, boolean, String) is deprecated - " +
                "will not be replaced");
        Collection<PathObject> detections = QP.getDetectionObjects();
        return sendPathObjectsToOmero(imageServer, detections, deleteROIsOnOMERO, owner);
    }

    /**
     * Send a collection of pathObjects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @param owner the owner of the ROIs to delete. If null, then all ROIs are deleted whatever the owner
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects,
                                                 boolean deleteROIsOnOMERO, String owner) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String) is deprecated - " +
                "use sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean) instead");
        List<ROIWrapper> rois = sendPathObjectsToOmero(imageServer, pathObjects, deleteROIsOnOMERO, owner, true);
        return rois != null && !rois.isEmpty();
    }

    /**
     * Send a collection of QuPath objects (annotations and/or detections) to OMERO, without deleting existing ROIs
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param pathObjects QuPath annotations or detections objects
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, Collection<PathObject> pathObjects) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer, Collection) is deprecated - " +
                "use sendPathObjectsToOmero(OmeroRawImageServer, Collection, boolean, String, boolean) instead");
        List<ROIWrapper> rois = sendPathObjectsToOmero(imageServer, pathObjects, false, Utils.ALL_USERS, true);
        return rois != null && !rois.isEmpty();
    }


    /**
     * Send all QuPath annotation objects to OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     *
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead.
     */
    @Deprecated
    public static boolean sendAnnotationsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        LogTools.warnOnce(logger, "sendAnnotationsToOmero(OmeroRawImageServer, boolean) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
        List<ROIWrapper> rois = sendAnnotationsToOmero(imageServer, deleteROIsOnOMERO, Utils.ALL_USERS, true);
        return rois != null && !rois.isEmpty();
    }

    /**
     * Send all QuPath objects (annotations and detections) to OMERO.
     * Be careful : the number of ROIs that can be displayed at the same time on OMERO is 500.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param deleteROIsOnOMERO Boolean to keep or delete ROIs on the current image on OMERO
     *
     * @return Sending status (true if ROIs have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean)} instead.
     */
    @Deprecated
    public static boolean sendPathObjectsToOmero(OmeroRawImageServer imageServer, boolean deleteROIsOnOMERO) {
        LogTools.warnOnce(logger, "sendPathObjectsToOmero(OmeroRawImageServer, boolean) is deprecated - " +
                "use sendAnnotationsToOmero(OmeroRawImageServer, boolean, String, boolean) instead");
        List<ROIWrapper> rois = sendAnnotationsToOmero(imageServer, deleteROIsOnOMERO, Utils.ALL_USERS, true);
        return rois != null && !rois.isEmpty();
    }

    /**
     * This method creates an instance of simple-omero-client object to get access to the full simple-omero-client API,
     * developed by Pierre Pouchin (<a href="https://github.com/GReD-Clermont/simple-omero-client">...</a>).
     *
     * @param imageServer : ImageServer of an image loaded from OMERO
     *
     * @return fr.igred.omero.Client object
     * @deprecated use {@link OmeroRawClient#getSimpleClient()} instead (accessible from {@link OmeroRawImageServer#getClient()})
     */
    @Deprecated
    public static Client getSimpleOmeroClientInstance(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "getSimpleOmeroClientInstance(OmeroRawImageServer) is deprecated - " +
                "use OmeroRawClient.getSimpleClient() instead");
        return imageServer.getClient().getSimpleClient();
    }

    /**
     * Import all ROIs linked to the current image from OMERO to QuPath.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param removePathObjects Boolean to delete or keep pathObjects (annotations, detections) on the current image.
     *
     * @return The list of OMERO rois converted into pathObjects
     * @deprecated use {@link OmeroRawScripting#addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer, boolean removePathObjects) {
        LogTools.warnOnce(logger, "importOmeroROIsToQuPath(OmeroRawImageServer, boolean) is deprecated - " +
                "use addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean) instead");
        return addROIsToQuPath(imageServer, removePathObjects, Utils.ALL_USERS, true);
    }

    /**
     * Import ROIs owned by the specified owner and linked to the current image from OMERO to QuPath
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param removePathObjects Boolean to delete or keep pathObjects (annotations, detections) on the current image.
     * @param owner owner of the ROis to filter
     *
     * @return The list of OMERO rois converted into pathObjects
     * @deprecated use {@link OmeroRawScripting#addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean)} instead
     */
    @Deprecated
    public static Collection<PathObject> importOmeroROIsToQuPath(OmeroRawImageServer imageServer,
                                                                 boolean removePathObjects, String owner) {
        LogTools.warnOnce(logger, "importOmeroROIsToQuPath(OmeroRawImageServer, boolean, String) is deprecated - " +
                "use addROIsToQuPath(OmeroRawImageServer, boolean, String, boolean) instead");
        return addROIsToQuPath(imageServer, removePathObjects, owner, true);
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
     * @deprecated use {@link OmeroRawScripting#sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static boolean sendMetadataOnOmero(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendMetadataOnOmero(Map, OmeroRawImageServer) is deprecated - " +
                "use sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean) instead");
        Map<String, Map<String, String>> results = sendQPMetadataToOmero(qpMetadata, imageServer, Utils.UpdatePolicy.KEEP_KEYS, Utils.UpdatePolicy.KEEP_KEYS,true);
        return !(results.get(Utils.KVP_KEY).isEmpty() || results.get(Utils.TAG_KEY).isEmpty());
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
     * @deprecated use {@link OmeroRawScripting#sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static boolean sendMetadataOnOmeroAndDeleteKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendMetadataOnOmeroAndDeleteKeyValues(Map, OmeroRawImageServer) is deprecated - " +
                "use sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean) instead");
        Map<String, Map<String, String>> results = sendQPMetadataToOmero(qpMetadata, imageServer, Utils.UpdatePolicy.DELETE_KEYS, Utils.UpdatePolicy.KEEP_KEYS, true);
        return !(results.get(Utils.KVP_KEY).isEmpty() || results.get(Utils.TAG_KEY).isEmpty());
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
     * @deprecated use {@link OmeroRawScripting#sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static boolean sendMetadataOnOmeroAndUpdateKeyValues(Map<String, String> qpMetadata, OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "sendMetadataOnOmeroAndUpdateKeyValues(Map, OmeroRawImageServer) is deprecated - " +
                "use sendQPMetadataToOmero(Map, OmeroRawImageServer, Utils.UpdatePolicy, Utils.UpdatePolicy, boolean) instead");
        Map<String, Map<String, String>> results = sendQPMetadataToOmero(qpMetadata, imageServer, Utils.UpdatePolicy.UPDATE_KEYS, Utils.UpdatePolicy.KEEP_KEYS, true);
        return !(results.get(Utils.KVP_KEY).isEmpty() || results.get(Utils.TAG_KEY).isEmpty());
    }


    /**
     * Read, from OMERO, Key-Value pairs attached to the current image and check if all keys are unique. If they are not unique, no Key-Values are returned.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return map of OMERO Key-Value pairs
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static Map<String,String> importOmeroKeyValues(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "importOmeroKeyValues(OmeroRawImageServer) is deprecated - " +
                "use addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        // read current key-value on OMERO
        List<NamedValue> currentOmeroKeyValues = OmeroRawTools.readKeyValuesAsNamedValue(imageServer.getClient(), imageServer.getId());

        if (currentOmeroKeyValues.isEmpty()) {
            Dialogs.showWarningNotification("Read key values on OMERO", "The current image does not have any KeyValues on OMERO");
            return new HashMap<>();
        }

        // check unique keys
        boolean uniqueOmeroKeys = Utils.checkUniqueKeyInAnnotationMap(currentOmeroKeyValues);

        if (!uniqueOmeroKeys) {
            Dialogs.showErrorMessage("Keys not unique", "There are at least two identical keys on OMERO. Please make each key unique");
            return null;
        }

        return currentOmeroKeyValues.stream().collect(Collectors.toMap(e->e.name, e->e.value));
    }

    /**
     * Send a list of tags to OMERO. If tags are already attached to the image, these tags are not sent.
     *
     * @param tags List of tags to add to the image
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if tags have been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendTagsToOmero(List, OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     *
     */
    @Deprecated
    public static boolean sendTagsToOmero(List<String> tags, OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "sendTagsToOmero(List, OmeroRawImageServer) is deprecated - " +
                "use sendTagsToOmero(List, OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        return sendTagsToOmero(tags, imageServer, Utils.UpdatePolicy.KEEP_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addMetadata(Map<String,String> keyValues) {
        LogTools.warnOnce(logger, "addMetadata(Map) is deprecated - " +
                "use addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(QP.getProjectEntry(), keyValues, Utils.UpdatePolicy.KEEP_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addOmeroKeyValues(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "addOmeroKeyValues(OmeroRawImageServer) is deprecated - " +
                "use addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(imageServer, Utils.UpdatePolicy.KEEP_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addAndUpdateMetadata(Map<String,String> keyValues) {
        LogTools.warnOnce(logger, "addAndUpdateMetadata(Map) is deprecated - " +
                "use addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(QP.getProjectEntry(), keyValues, Utils.UpdatePolicy.UPDATE_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addOmeroKeyValuesAndUpdateMetadata(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "addOmeroKeyValuesAndUpdateMetadata(OmeroRawImageServer) is deprecated - " +
                "use addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(imageServer, Utils.UpdatePolicy.UPDATE_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addOmeroKeyValuesAndDeleteMetadata(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "addOmeroKeyValuesAndDeleteMetadata(OmeroRawImageServer) is deprecated - " +
                "use addKeyValuesToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(imageServer, Utils.UpdatePolicy.DELETE_KEYS, true);
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
     * @deprecated use {@link OmeroRawScripting#addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static void addAndDeleteMetadata(Map<String,String> keyValues) {
        LogTools.warnOnce(logger, "addAndDeleteMetadata(Map) is deprecated - " +
                "use addKeyValuesToQuPath(ProjectImageEntry, Map, Utils.UpdatePolicy, boolean) instead");
        addKeyValuesToQuPath(QP.getProjectEntry(), keyValues, Utils.UpdatePolicy.DELETE_KEYS, true);
    }


    /**
     * Read, from OMERO, tags attached to the current image and add them as QuPath metadata fields
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return list of OMERO tags.
     * @deprecated use {@link  OmeroRawScripting#addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean)} instead
     */
    @Deprecated
    public static List<String> addTagsToQuPath(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "addTagsToQuPath(OmeroRawImageServer) is deprecated - " +
                "use addTagsToQuPath(OmeroRawImageServer, Utils.UpdatePolicy, boolean) instead");
        return addTagsToQuPath(imageServer, Utils.UpdatePolicy.KEEP_KEYS,true);
    }


    /**
     * Send all detections measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendDetectionMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendDetectionMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendDetectionMeasurementTable(OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendDetectionMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendDetectionMeasurementsToOmero(imageServer, QP.getDetectionObjects(), imageData, false, Utils.ALL_USERS, true) > 0;
    }


    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table with a default table name referring to detections
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendDetectionMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendDetectionMeasurementTable(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer,
                                                        ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendDetectionMeasurementTable(Collection, OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendDetectionMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendDetectionMeasurementsToOmero(imageServer, detectionObjects, imageData, false, Utils.ALL_USERS, true) > 0;
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param tableName Name of the table to upload
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, String, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendMeasurementTableToOmero(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer,
                                                      ImageData<BufferedImage> imageData, String tableName){
        LogTools.warnOnce(logger, "sendMeasurementTableToOmero(Collection, OmeroRawImageServer, ImageData, String) is deprecated - " +
                "use sendMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, String, boolean, String, boolean) instead");
        return sendMeasurementsToOmero(imageServer,annotationObjects,  imageData, tableName, false, null, true) > 0;
    }

    /**
     * Send all annotations measurements to OMERO as an OMERO.table
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationMeasurementTable(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendAnnotationMeasurementTable(OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendAnnotationMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendAnnotationMeasurementsToOmero(imageServer, QP.getAnnotationObjects(), imageData, false, Utils.ALL_USERS, true) > 0;
    }

    /**
     * Send pathObjects' measurements to OMERO as an OMERO.table  with a default table name referring to annotations
     *
     * @param annotationObjects QuPath annotations objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the OMERO.table has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationMeasurementTable(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer,
                                                         ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendAnnotationMeasurementTable(Collection, OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendAnnotationMeasurementsToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendAnnotationMeasurementsToOmero(imageServer, annotationObjects, imageData,  false, Utils.ALL_USERS, true) > 0;
    }

    /**
     * Send all detections measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendDetectionMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendDetectionMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendDetectionMeasurementTableAsCSV(OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendDetectionMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendDetectionMeasurementsAsCSVToOmero(imageServer, QP.getDetectionObjects(), imageData, false, Utils.ALL_USERS, true) > 0;
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param detectionObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendDetectionMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendDetectionMeasurementTableAsCSV(Collection<PathObject> detectionObjects, OmeroRawImageServer imageServer,
                                                             ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendDetectionMeasurementTableAsCSV(Collection, OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendDetectionMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendDetectionMeasurementsAsCSVToOmero(imageServer, detectionObjects, imageData, false, Utils.ALL_USERS, true) > 0;
    }

    /**
     * Send all annotations measurements to OMERO as a CSV file
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationMeasurementTableAsCSV(OmeroRawImageServer imageServer, ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendAnnotationMeasurementTableAsCSV(OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendAnnotationMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendAnnotationMeasurementsAsCSVToOmero(imageServer, QP.getAnnotationObjects(), imageData, false, Utils.ALL_USERS, true) > 0;
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param annotationObjects QuPath annotation objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendAnnotationMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendAnnotationMeasurementTableAsCSV(Collection<PathObject> annotationObjects, OmeroRawImageServer imageServer,
                                                              ImageData<BufferedImage> imageData){
        LogTools.warnOnce(logger, "sendAnnotationMeasurementTableAsCSV(Collection, OmeroRawImageServer, ImageData) is deprecated - " +
                "use sendAnnotationMeasurementsAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, boolean, String, boolean) instead");
        return sendAnnotationMeasurementsAsCSVToOmero(imageServer, annotationObjects, imageData, false, Utils.ALL_USERS, true) > 0;
    }


    /**
     * Send pathObjects' measurements to OMERO as a CSV file with a default table name referring to annotation
     *
     * @param pathObjects QuPath detection objects
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param imageData QuPath image
     * @param filename Name of the file to upload
     * @return Sending status (true if the CSV file has been sent ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendMeasurementAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, String, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendMeasurementTableAsCSVToOmero(Collection<PathObject> pathObjects, OmeroRawImageServer imageServer,
                                                           ImageData<BufferedImage> imageData, String filename){
        LogTools.warnOnce(logger, "sendMeasurementTableAsCSVToOmero(Collection, OmeroRawImageServer, ImageData, String) is deprecated - " +
                "use sendMeasurementAsCSVToOmero(OmeroRawImageServer, Collection, ImageData, String, boolean, String, boolean) instead");
        return sendMeasurementAsCSVToOmero(imageServer, pathObjects, imageData, filename, false, null, true) > 0;
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @deprecated use {@link OmeroRawScripting#deleteDetectionMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "deleteDetectionFiles(OmeroRawImageServer) is deprecated - " +
                "use deleteDetectionMeasurements(OmeroRawImageServer, String, boolean) instead");
        deleteDetectionMeasurements(imageServer, Utils.ALL_USERS, true);
    }

    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @deprecated use {@link OmeroRawScripting#deleteDetectionMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        LogTools.warnOnce(logger, "deleteDetectionFiles(OmeroRawImageServer, Collection) is deprecated - " +
                "use deleteDetectionMeasurements(OmeroRawImageServer, String, boolean) instead");
        deleteDetectionFiles(imageServer, files, null);
    }


    /**
     * Delete all previous version of detection tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @deprecated use {@link OmeroRawScripting#deleteDetectionMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteDetectionFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files, String owner){
        LogTools.warnOnce(logger, "deleteDetectionFiles(OmeroRawImageServer, Collection, String) is deprecated - " +
                "use deleteDetectionMeasurements(OmeroRawImageServer, String, boolean) instead");
        try{
            List<FileAnnotationWrapper> fileWrappers = files.stream().map(FileAnnotationWrapper::new).collect(Collectors.toList());
            String name = detectionFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, FileAnnotationData.MS_EXCEL, owner);
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, TablesFacility.TABLES_MIMETYPE, owner);
        }catch(OMEROServerError | DSAccessException | InterruptedException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Delete detection measurements", "Cannot delete the project's detection measurements from image "+imageServer.getId(), true);
        }
    }


    /**
     * Delete all previous version of a file, identified by the name given in parameters. This name may or may not be the
     * full name of the files to delete.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param name contained in the table/file name to delete (i.e. filtering item). It may be a part of the full table/file name
     * @deprecated use {@link Client#delete(Collection)} instead
     */
    @Deprecated
    public static void deleteFiles(OmeroRawImageServer imageServer, String name){
        LogTools.warnOnce(logger, "deleteFiles(OmeroRawImageServer, String) is deprecated - " +
                "use Client.delete(Collection) instead");
        try{
            List<FileAnnotationWrapper> fileWrappers = imageServer.getImageWrapper().getFileAnnotations(imageServer.getClient().getSimpleClient());
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, null, null);
        }catch(OMEROServerError | DSAccessException | InterruptedException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Delete files", "Cannot delete the files '"+name+"' from image "+imageServer.getId(), true);
        }
    }


    /**
     * Return the files as FileAnnotationData attached to the current image from OMERO.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return a list of read FileAnnotationData
     * @deprecated use {@link ImageWrapper#getFileAnnotations(Client)} instead
     */
    @Deprecated
    public static List<FileAnnotationData> readFilesAttachedToCurrentImageOnOmero(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "readFilesAttachedToCurrentImageOnOmero(OmeroRawImageServer) is deprecated - " +
                "use ImageWrapper.getFileAnnotations(Client) instead");
        try {
            return imageServer.getImageWrapper().getFileAnnotations(imageServer.getClient().getSimpleClient())
                    .stream()
                    .map(FileAnnotationWrapper::asDataObject)
                    .collect(Collectors.toList());
        }catch(DSAccessException | ExecutionException | ServiceException e){
            return Collections.emptyList();
        }
    }

    /**
     * Link a FileAnnotationData to an OMERO container
     * The FileAnnotationData must already have a valid ID on OMERO (i.e. already existing in the OMERO database)
     *
     * @param client OmeroRawClient object to handle OMERO connection
     * @param fileAnnotationData annotation to link
     * @param container on OMERO
     * @return the linked FileAnnotationData
     * @deprecated use {@link fr.igred.omero.repository.GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    protected static FileAnnotationData linkFile(OmeroRawClient client, FileAnnotationData fileAnnotationData, DataObject container){
        LogTools.warnOnce(logger, "linkFile(OmeroRawImageServer, FileAnnotation) is deprecated - " +
                "use fr.igred.omero.repository.GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
        return (FileAnnotationData) OmeroRawTools.linkAnnotationToOmero(client, fileAnnotationData, container);
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are retrieved from the corresponding image on OMERO and filtered according to the current
     * QuPath project
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @deprecated use {@link OmeroRawScripting#deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "deleteAnnotationFiles(OmeroRawImageServer) is deprecated - " +
                "use deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean) instead");
        deleteAnnotationMeasurements(imageServer, Utils.ALL_USERS, true);
    }


    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @deprecated use {@link OmeroRawScripting#deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files){
        LogTools.warnOnce(logger, "deleteAnnotationFiles(OmeroRawImageServer, Collection) is deprecated - " +
                "use deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean) instead");
        deleteAnnotationFiles(imageServer, files, null);
    }

    /**
     * Delete all previous version of annotation tables (OMERO and csv files) related to the current QuPath project.
     * Files to delete are filtered according to the current QuPath project.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @param files List of files to browse
     * @param owner the owner of the files to delete. If null, then all tables are deleted whatever the owner
     * @deprecated {@link OmeroRawScripting#deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean)} instead
     */
    @Deprecated
    public static void deleteAnnotationFiles(OmeroRawImageServer imageServer, Collection<FileAnnotationData> files, String owner){
        LogTools.warnOnce(logger, "deleteAnnotationFiles(OmeroRawImageServer, Collection, String) is deprecated - " +
                "use deleteAnnotationMeasurements(OmeroRawImageServer, String, boolean) instead");
        try{
            List<FileAnnotationWrapper> fileWrappers = files.stream().map(FileAnnotationWrapper::new).collect(Collectors.toList());
            String name = annotationFileBaseName + "_" + QPEx.getQuPath().getProject().getName().split("/")[0];
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, FileAnnotationData.MS_EXCEL, owner);
            deletePreviousFileVersions(imageServer.getClient(), fileWrappers, name, TablesFacility.TABLES_MIMETYPE, owner);
        }catch(OMEROServerError | DSAccessException | InterruptedException | ExecutionException | DSOutOfServiceException e){
            Utils.errorLog(logger, "Delete detection measurements", "Cannot delete the project's detection measurements from image "+imageServer.getId(), true);
        }
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
     * @deprecated use {@link OmeroRawScripting#sendParentMeasurementsToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendParentMeasurementTableAsOmeroTable(LinkedHashMap<String, List<String>> parentTable,
                                                                 OmeroRawClient client, Collection<DataObject> parents,
                                                                 boolean deletePreviousTable){
        LogTools.warnOnce(logger, "sendParentMeasurementTableAsOmeroTable(LinkedHashMap, List, OmeroRawClient, Collection, boolean) is deprecated - " +
                "use sendParentMeasurementsToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean) instead");
        return sendParentMeasurementTableAsOmeroTable(parentTable, client, parents, deletePreviousTable, Utils.ALL_USERS);
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
     * @deprecated use {@link OmeroRawScripting#sendParentMeasurementsToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendParentMeasurementTableAsOmeroTable(LinkedHashMap<String, List<String>> parentTable,
                                                                 OmeroRawClient client, Collection<DataObject> parents,
                                                                 boolean deletePreviousTable, String owner){
        LogTools.warnOnce(logger, "sendParentMeasurementTableAsOmeroTable(LinkedHashMap, List, OmeroRawClient, Collection, boolean, String) is deprecated - " +
                "use sendParentMeasurementsToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean) instead");
        Collection<GenericRepositoryObjectWrapper<?>> parentWrappers = new ArrayList<>();
        for(DataObject parent : parents){
            if(parent instanceof DatasetData)
                parentWrappers.add(new DatasetWrapper((DatasetData) parent));
            else if(parent instanceof ProjectData)
                parentWrappers.add(new ProjectWrapper((ProjectData) parent));
            else if(parent instanceof WellData)
                parentWrappers.add(new WellWrapper((WellData) parent));
            else if(parent instanceof PlateData)
                parentWrappers.add(new PlateWrapper((PlateData) parent));
            else if(parent instanceof ScreenData)
                parentWrappers.add(new ScreenWrapper((ScreenData) parent));
            else Utils.warnLog(logger, "Send parent measurements",parent.getClass().getSimpleName() +"is not supported", false);
        }
        return sendParentMeasurementsToOmero(parentTable, client, parentWrappers, deletePreviousTable, owner, true) > 0;
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
     * @deprecated use {@link OmeroRawScripting#sendParentMeasurementsAsCSVToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendParentMeasurementTableAsCSV(LinkedHashMap<String, List<String>> parentTable,
                                                          OmeroRawClient client, Collection<DataObject> parents,
                                                          boolean deletePreviousTable){
        LogTools.warnOnce(logger, "sendParentMeasurementTableAsCSV(LinkedHashMap, List, OmeroRawClient, Collection, boolean) is deprecated - " +
                "use sendParentMeasurementsAsCSVToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean) instead");
        return sendParentMeasurementTableAsCSV(parentTable, client, parents, deletePreviousTable, Utils.ALL_USERS);
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
     * @deprecated use {@link OmeroRawScripting#sendParentMeasurementsAsCSVToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean)} instead
     */
    @Deprecated
    public static boolean sendParentMeasurementTableAsCSV(LinkedHashMap<String, List<String>> parentTable,
                                                          OmeroRawClient client, Collection<DataObject> parents,
                                                          boolean deletePreviousTable, String owner){
        LogTools.warnOnce(logger, "sendParentMeasurementTableAsCSV(LinkedHashMap, List, OmeroRawClient, Collection, boolean, String) is deprecated - " +
                "use sendParentMeasurementsAsCSVToOmero(LinkedHashMap, OmeroRawClient, Collection, boolean, String, boolean) instead");
        Collection<GenericRepositoryObjectWrapper<?>> parentWrappers = new ArrayList<>();
        for(DataObject parent : parents){
            if(parent instanceof DatasetData)
                parentWrappers.add(new DatasetWrapper((DatasetData) parent));
            else if(parent instanceof ProjectData)
                parentWrappers.add(new ProjectWrapper((ProjectData) parent));
            else if(parent instanceof WellData)
                parentWrappers.add(new WellWrapper((WellData) parent));
            else if(parent instanceof PlateData)
                parentWrappers.add(new PlateWrapper((PlateData) parent));
            else if(parent instanceof ScreenData)
                parentWrappers.add(new ScreenWrapper((ScreenData) parent));
            else Utils.warnLog(logger, "Send parent measurements",parent.getClass().getSimpleName() +"is not supported", false);
        }
        return sendParentMeasurementsAsCSVToOmero(parentTable, client, parentWrappers, deletePreviousTable, owner, true) > 0;
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
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void setChannelsDisplayRangeFromOmeroChannel(OmeroRawImageServer imageServer) {
        LogTools.warnOnce(logger, "setChannelsDisplayRangeFromOmeroChannel(OmeroRawImageServer) is deprecated - " +
                "use copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsDisplayRangeToQuPath(imageServer, QPEx.getQuPath().getImageData(), true);
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
     * @param qpNotif true to display a QuPath notification
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer imageServer, boolean qpNotif) {
        LogTools.warnOnce(logger, "copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer, boolean) is deprecated - " +
                "use copyOmeroChannelsDisplayRangeToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsDisplayRangeToQuPath(imageServer, QPEx.getQuPath().getImageData(), qpNotif);
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
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsColorToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void setChannelsColorFromOmeroChannel(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "setChannelsColorFromOmeroChannel(OmeroRawImageServer) is deprecated - " +
                "use copyOmeroChannelsColorToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsColorToQuPath(imageServer, QPEx.getQuPath().getImageData(), true);
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
     * @param qpNotif true to display a QuPath notification
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsColorToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void copyOmeroChannelsColorToQuPath(OmeroRawImageServer imageServer,  boolean qpNotif){
        LogTools.warnOnce(logger, "copyOmeroChannelsColorToQuPath(OmeroRawImageServer, boolean) is deprecated - " +
                "use copyOmeroChannelsColorToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsColorToQuPath(imageServer, QPEx.getQuPath().getImageData(), qpNotif);
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
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsNameToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void setChannelsNameFromOmeroChannel(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "setChannelsNameFromOmeroChannel(OmeroRawImageServer) is deprecated - " +
                "use copyOmeroChannelsNameToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsNameToQuPath(imageServer, QPEx.getQuPath().getImageData(), true);
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
     * @param qpNotif true to display a QuPath notification
     * @deprecated use {@link OmeroRawScripting#copyOmeroChannelsNameToQuPath(OmeroRawImageServer, ImageData, boolean)} instead
     */
    @Deprecated
    public static void copyOmeroChannelsNameToQuPath(OmeroRawImageServer imageServer, boolean qpNotif){
        LogTools.warnOnce(logger, "copyOmeroChannelsNameToQuPath(OmeroRawImageServer, boolean) is deprecated - " +
                "use copyOmeroChannelsNameToQuPath(OmeroRawImageServer, ImageData, boolean) instead");
        copyOmeroChannelsColorToQuPath(imageServer, QPEx.getQuPath().getImageData(), qpNotif);
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
     * @deprecated use {@link OmeroRawScripting#sendQuPathChannelsDisplayRangeToOmero(OmeroRawImageServer, boolean)} instead
     */
    @Deprecated
    public static boolean sendChannelsDisplayRangeToOmero(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "sendChannelsDisplayRangeToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendQuPathChannelsDisplayRangeToOmero(OmeroRawImageServer, boolean) instead");
        return sendQuPathChannelsDisplayRangeToOmero(imageServer, true);
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
     * @deprecated use {@link OmeroRawScripting#sendQuPathChannelsNameToOmero(OmeroRawImageServer, boolean)} instead
     */
    @Deprecated
    public static boolean sendChannelsNameToOmero(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "sendChannelsNameToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendQuPathChannelsNameToOmero(OmeroRawImageServer, boolean) instead");
        return sendQuPathChannelsNameToOmero(imageServer, true);
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
     * @deprecated use {@link OmeroRawScripting#sendQuPathChannelsColorToOmero(OmeroRawImageServer, boolean)} instead
     */
    @Deprecated
    public static boolean sendChannelsColorToOmero(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "sendChannelsColorToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendQuPathChannelsColorToOmero(OmeroRawImageServer, boolean) instead");
        return sendQuPathChannelsColorToOmero(imageServer, true);
    }


    /**
     * Set the name the image on OMERO, based on QuPath settings.
     *
     * @param imageServer ImageServer of an image loaded from OMERO
     * @return Sending status (true if the image name on OMERO has been updated ; false if there were troubles during the sending process)
     * @deprecated use {@link OmeroRawScripting#sendQuPathImageNameToOmero(OmeroRawImageServer, boolean)} instead
     */
    @Deprecated
    public static boolean sendImageNameToOmero(OmeroRawImageServer imageServer){
        LogTools.warnOnce(logger, "sendImageNameToOmero(OmeroRawImageServer) is deprecated - " +
                "use sendQuPathImageNameToOmero(OmeroRawImageServer, boolean) instead");
        return sendQuPathImageNameToOmero(imageServer, true);
    }

}