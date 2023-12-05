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

package qupath.ext.biop.servers.omero.raw.utils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.AnnotationList;
import fr.igred.omero.annotations.GenericAnnotationWrapper;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PlateWrapper;
import fr.igred.omero.roi.ROIWrapper;
import loci.formats.in.DefaultMetadataOptions;
import loci.formats.in.MetadataLevel;
import ome.formats.OMEROMetadataStoreClient;
import ome.formats.importer.ImportCandidates;
import ome.formats.importer.ImportConfig;
import ome.formats.importer.ImportContainer;
import ome.formats.importer.ImportLibrary;
import ome.formats.importer.OMEROWrapper;
import ome.formats.importer.cli.ErrorHandler;
import ome.formats.importer.cli.LoggingImportMonitor;
import omero.RLong;
import omero.ServerError;
import omero.api.IQueryPrx;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;

import omero.gateway.facility.BrowseFacility;
import omero.gateway.facility.DataManagerFacility;
import omero.gateway.facility.MetadataFacility;
import omero.gateway.facility.ROIFacility;
import omero.gateway.facility.TablesFacility;

import omero.gateway.facility.TransferFacility;
import omero.gateway.model.AnnotationData;
import omero.gateway.model.ChannelData;
import omero.gateway.model.DataObject;
import omero.gateway.model.DatasetData;
import omero.gateway.model.ExperimenterData;
import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.GroupData;
import omero.gateway.model.ImageData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PixelsData;
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ROIData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.gateway.model.WellData;
import omero.model.Dataset;
import omero.model.DatasetI;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.IObject;
import omero.model.Image;
import omero.model.NamedValue;
import omero.model.Pixels;
import omero.model.PlateI;
import omero.model.ProjectDatasetLink;
import omero.model.ProjectDatasetLinkI;
import omero.model.ProjectI;
import omero.model.RenderingDef;
import omero.model.Shape;
import omero.model.TagAnnotation;
import omero.model.WellSample;
import omero.sys.ParametersI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.PathObject;

import javax.imageio.ImageIO;

import static omero.rtypes.rint;


/**
 * Static helper methods related to OMERORawImageServer.
 *
 * @author Melvin Gelbard
 *
 */
public final class OmeroRawTools {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawTools.class);

    private final static String noImageThumbnail = "NoImage256.png";


    private final static String um = GeneralTools.micrometerSymbol();

    /**
     * Suppress default constructor for non-instantiability
     */
    private OmeroRawTools() {
        throw new AssertionError();
    }

    /**
     * Retrieve a user on OMERO based on its ID
     *
     * @param client
     * @param userId
     * @param username
     * @return The specified OMERO user
     * @deprecated use {@link OmeroRawTools#getUser(OmeroRawClient, long)} instead
     */
    @Deprecated
    public static ExperimenterWrapper getOmeroUser(OmeroRawClient client, long userId, String username){
        try {
            return client.getSimpleClient().getUser(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user "+username +" ; id: "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve a user on OMERO based on its ID
     *
     * @param client
     * @param userId
     * @return The specified OMERO user
     */
    public static ExperimenterWrapper getUser(OmeroRawClient client, long userId){
        try {
            return client.getSimpleClient().getUser(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user from id: "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve a user on OMERO based on its name
     *
     * @param client
     * @param username
     * @return The specified OMERO user
     */
    public static ExperimenterWrapper getUser(OmeroRawClient client, String username){
        try {
            return client.getSimpleClient().getUser(username);
        } catch (AccessException | ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user : "+username);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }



    /**
     * Retrieve all users within the group on OMERO
     *
     * @param client
     * @param groupId
     * @return A list of all OMERO user within the specified group
     */
    @Deprecated
    public static List<Experimenter> getOmeroUsersInGroup(OmeroRawClient client, long groupId){
            return getGroupUsers(client, groupId).stream()
                    .map(ExperimenterWrapper::asDataObject)
                    .map(ExperimenterData::asExperimenter).
                    collect(Collectors.toList());
    }

    public static List<ExperimenterWrapper> getGroupUsers(OmeroRawClient client, long groupId){
        try {
            return client.getSimpleClient().getGroup(groupId).getExperimenters();
        } catch (ServiceException | OMEROServerError e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO users in group "+groupId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException("Cannot read OMERO users in group "+groupId, e);
        }
    }


    /**
     * Retrieve a group on OMERO based on its id
     *
     * @param client
     * @param groupId
     * @return The specified OMERO group
     */
    public static GroupWrapper getGroup(OmeroRawClient client, long groupId){
        try {
            return client.getSimpleClient().getGroup(groupId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO group with id: "+groupId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve a group on OMERO based on its name
     *
     * @param client
     * @param groupName
     * @return The specified OMERO group
     */
    public static GroupWrapper getGroup(OmeroRawClient client, String groupName){
        try {
            return client.getSimpleClient().getGroup(groupName);
        } catch (AccessException | ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO group : "+groupName);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve a group on OMERO based on its id
     *
     * @param client
     * @param groupId
     * @return The specified OMERO group
     */
    @Deprecated
    public static ExperimenterGroup getOmeroGroup(OmeroRawClient client, long groupId, String username){
            return getGroup(client, groupId).asDataObject().asGroup();
    }

    /**
     * get all the groups the logged-in user is member of
     *
     * @param client
     * @return The list of user's OMERO groups
     */
    public static List<GroupWrapper> getUserGroups(OmeroRawClient client) {
        return client.getLoggedInUser().getGroups();
    }

    /**
     * get all the groups the certain user is member of
     *
     * @param client
     * @return The list of user's OMERO groups
     */
    public static List<GroupWrapper> getUserGroups(OmeroRawClient client, long userId) {
        return getUser(client, userId).getGroups();
    }

    /**
     * get all the groups where the current user is member of
     *
     * @param client
     * @param userId
     * @return The list of user's OMERO groups
     */
    @Deprecated
    public static List<ExperimenterGroup> getUserOmeroGroups(OmeroRawClient client, long userId) {
        return getUserGroups(client).stream()
                .map(GroupWrapper::asDataObject)
                .map(GroupData::asGroup)
                .collect(Collectors.toList());
    }


    /**
     * get all the groups on OMERO server. This functionality is reserved to Admin people. In case you are not
     * Admin, {@link #getUserGroups(OmeroRawClient)} method is called instead.
     *
     * @param client
     * @return The list of all groups on OMERO server
     */
    @Deprecated
    public static List<ExperimenterGroup> getAllOmeroGroups(OmeroRawClient client) {
        return getAllGroups(client).stream()
                .map(GroupWrapper::asDataObject)
                .map(GroupData::asGroup)
                .collect(Collectors.toList());
    }

    /**
     * get all the groups on OMERO server. This functionality is reserved to Admin people. In case you are not
     * Admin, {@link #getUserGroups(OmeroRawClient client)} method is called instead.
     *
     * @param client
     * @return The list of all groups on OMERO server
     */
    public static List<GroupWrapper> getAllGroups(OmeroRawClient client) {
        try {
            if(client.isAdmin()) {
                List<ExperimenterGroup> allGroups = client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).lookupGroups();
                 return allGroups.stream().map(GroupData::new).map(GroupWrapper::new).collect(Collectors.toList());
            }
            else {
                Dialogs.showWarningNotification("OMERO admin", "You are not allowed to see all OMERO groups. Only available groups for you are loaded");
                return getUserGroups(client);
            }
        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorMessage("OMERO admin", "Cannot retrieve all OMERO groups");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Get the default OMERO group of the specified user
     *
     * @param client
     * @param userId
     * @return User's OMERO default group
     */
    @Deprecated
    public static ExperimenterGroup getDefaultOmeroGroup(OmeroRawClient client, long userId) {
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).getDefaultGroup(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read the default OMERO group for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }


    /**
     * Retrieve the group of which an image is part of.
     *
     * @param client
     * @param imageId
     * @return The group id
     */
    public static long getGroupIdFromImageId(OmeroRawClient client, long imageId){
        try {
            // request an imageData object for the image id by searching in all groups on OMERO
            // take care if the user is admin or not
            ImageData img = (ImageData) client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findObject(client.getSimpleClient().getCtx(), "ImageData", imageId, true);
            return img.getGroupId();

        } catch (DSOutOfServiceException | NoSuchElementException | ExecutionException | DSAccessException e) {
            Dialogs.showErrorNotification("Get group id","Cannot retrieved group id from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return -1;
        }
    }


    /**
     * Get user's orphaned datasets from the OMERO server
     *
     * @param client the client {@link OmeroRawClient} object
     * @param user
     * @return List orphaned of datasets
     */
    public static Collection<DatasetWrapper> readOrphanedDatasets(OmeroRawClient client, ExperimenterWrapper user)
            throws ServiceException, OMEROServerError, AccessException, ExecutionException {
        // query orphaned dataset
        List<IObject> datasetObjects = client.getSimpleClient().findByQuery("select dataset from Dataset as dataset " +
                "join fetch dataset.details.owner as o " +
                "where o.id = " + user.getId() +
                "and not exists (select obl from " +
                "ProjectDatasetLink as obl where obl.child = dataset.id) ");

        // get orphaned dataset ids
        Long[] datasetIds = datasetObjects.stream()
                .map(IObject::getId)
                .map(RLong::getValue)
                .toArray(Long[]::new);

        // get orphaned datasets
        return client.getSimpleClient().getDatasets(datasetIds);
    }


    /**
     * Get user's orphaned images from the OMERO server
     *
     * @param client
     * @param user
     * @return List of orphaned images
     */
    public static Collection<ImageWrapper> readOrphanedImages(OmeroRawClient client, ExperimenterWrapper user)
            throws ExecutionException {
            return client.getSimpleClient()
                    .getGateway()
                    .getFacility(BrowseFacility.class)
                    .getOrphanedImages(client.getSimpleClient().getCtx(), user.getId())
                    .stream()
                    .map(ImageWrapper::new)
                    .collect(Collectors.toList());
    }

    /**
     * Retrieve parents of OMERO containers (i.e. Image, Dataset, Well and Plate). For Project, Screen and other, it
     * returns an empty list.
     *
     * @param client
     * @param dataType image or container denomination (no case sensitive)
     * @param id image or container id
     * @return List of object's parent(s) or empty list
     */
    public static Collection<? extends DataObject> getParent(OmeroRawClient client, String dataType, long id){
        try{
            switch(dataType.toLowerCase()) {
                case "image":
                    // get the image
                    Image image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), id).asImage();

                    // get the parent datasets
                    List<IObject> datasetObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from DatasetImageLink as link " +
                                    "where link.child=" + id, null);

                    if(!datasetObjects.isEmpty()) {
                        logger.info("The current image " + id + " has a dataset as parent");
                        // get projects' id
                        List<Long> ids = datasetObjects.stream()
                                .map(IObject::getId)
                                .map(RLong::getValue)
                                .distinct()
                                .collect(Collectors.toList());

                        return client.getSimpleClient().getGateway()
                                .getFacility(BrowseFacility.class)
                                .getDatasets(client.getSimpleClient().getCtx(), ids);
                    }else{
                        logger.info("The current image " + id + " has a well as parent");

                        List<IObject> wellSamplesObjects = client.getSimpleClient().getGateway()
                                .getQueryService(client.getSimpleClient().getCtx())
                                .findAllByQuery("select ws from WellSample ws where image=" + id, null);

                        List<Long> ids = wellSamplesObjects.stream()
                                .map(WellSample.class::cast)
                                .map(WellSample::getWell)
                                .map(IObject::getId)
                                .map(RLong::getValue)
                                .collect(Collectors.toList());

                        if(!ids.isEmpty())
                            return client.getSimpleClient().getGateway()
                                    .getFacility(BrowseFacility.class)
                                    .getWells(client.getSimpleClient().getCtx(), ids);
                        else {
                            Dialogs.showErrorNotification("Getting parent of image", "The current image " + id + " has no parent.");
                            break;
                        }
                    }

                case "dataset":
                    // get the parent projects
                    List<IObject> projectObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from ProjectDatasetLink as link " +
                                    "where link.child=" + id, null);

                    // get projects' id
                    List<Long> projectIds = projectObjects.stream()
                            .map(IObject::getId)
                            .map(RLong::getValue)
                            .distinct()
                            .collect(Collectors.toList());

                    return client.getSimpleClient().getGateway()
                            .getFacility(BrowseFacility.class)
                            .getProjects(client.getSimpleClient().getCtx(), projectIds);

                case "well":
                    return Collections.singletonList(client.getSimpleClient().getGateway()
                                    .getFacility(BrowseFacility.class)
                                    .getWells(client.getSimpleClient().getCtx(), Collections.singletonList(id))
                                    .iterator()
                                    .next()
                                    .getPlate());

                case "plate":
                    // get parent screen
                    List<IObject> screenObjects = client.getSimpleClient().getGateway()
                            .getQueryService(client.getSimpleClient().getCtx())
                            .findAllByQuery("select link.parent from ScreenPlateLink as link " +
                                    "where link.child=" + id, null);

                    // get screens' id
                    List<Long> screenIds = screenObjects.stream()
                            .map(IObject::getId)
                            .map(RLong::getValue)
                            .distinct()
                            .collect(Collectors.toList());

                    return client.getSimpleClient().getGateway()
                            .getFacility(BrowseFacility.class)
                            .getScreens(client.getSimpleClient().getCtx(), screenIds);

                case "project":
                case "screen":
                    Dialogs.showWarningNotification("Getting parent","No parent for "+dataType+" id "+id);
                    break;
                default:
                    Dialogs.showWarningNotification("Getting parent","Unsupported object : "+dataType+" id "+id);
            }
        } catch (ServerError | DSOutOfServiceException | ExecutionException e) {
            Dialogs.showErrorNotification("Getting parent","Cannot retrieved the parent of "+dataType+" id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("Getting parent","You do not have access to "+dataType+" id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }

        return Collections.emptyList();
    }


    /**
     * Get the rendering settings object linked to the specified image.
     * Code partially copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getChannelColor} method
     *
     * @param client
     * @param imageId
     * @return Image's rendering settings object
     */
    public static RenderingDef readOmeroRenderingSettings(OmeroRawClient client, long imageId){
        try {
            // get pixel id
            long pixelsId = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId).getDefaultPixels().getId();
            // get rendering settings
            RenderingDef renderingDef = client.getSimpleClient().getGateway().getRenderingSettingsService(client.getSimpleClient().getCtx()).getRenderingSettings(pixelsId);

            if(renderingDef == null) {
                // load rendering settings if they were not automatically loaded
                RenderingEnginePrx re = client.getSimpleClient().getGateway().getRenderingService(client.getSimpleClient().getCtx(), pixelsId);
                re.lookupPixels(pixelsId);
                if (!(re.lookupRenderingDef(pixelsId))) {
                    re.resetDefaultSettings(true);
                    re.lookupRenderingDef(pixelsId);
                }
                re.load();
                re.close();
                return client.getSimpleClient().getGateway().getRenderingSettingsService(client.getSimpleClient().getCtx()).getRenderingSettings(pixelsId);
            }
            return renderingDef;

        } catch(ExecutionException | DSOutOfServiceException | ServerError | NullPointerException e){
            Dialogs.showErrorNotification("Rendering def reading","Could not read rendering settings on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Rendering def reading","You don't have the right to access to the Rendering setting of the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * Get all OMERO datasets corresponding to the list of ids
     *
     * @param client
     * @param plateIds
     * @return List of OMERO dataset objects
     */
    public static Collection<PlateWrapper> readPlates(OmeroRawClient client, List<Long> plateIds){

        String GET_PLATE_QUERY = "select p from Plate as p " +
                "left join fetch p.wells as w " +
                "left join fetch p.plateAcquisitions as pa " +
                "where p.id in (:ids)";
        try {
            IQueryPrx qs = client.getSimpleClient().getQueryService();
            ParametersI param = new ParametersI();

            param.addIds(plateIds);
            return qs.findAllByQuery(GET_PLATE_QUERY, param).stream()
                    .map(PlateI.class::cast)
                    .map(PlateData::new)
                    .map(PlateWrapper::new)
                    .collect(Collectors.toList());

        }catch(DSOutOfServiceException | ServerError e){
            Dialogs.showErrorNotification("Reading plates","An error occurs when reading OMERO plates "+plateIds);
            logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (NoSuchElementException e){
            Dialogs.showErrorNotification("Reading plates","You don't have the right to access OMERO plates "+plateIds);
            logger.error(e + "\n"+OmeroRawTools.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get an image from OMERO server, corresponding to the specified id.
     *
     * @param client
     * @param imageId
     * @return OMERO image object
     */
    public static ImageData readOmeroImage(OmeroRawClient client, long imageId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading image","An error occurs when reading OMERO image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch (DSAccessException | NoSuchElementException e){
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Get OMERO image's channels
     *
     * @param client
     * @param imageId
     * @return List of channels objects of the specified image
     */
    public static List<ChannelData> readOmeroChannels(OmeroRawClient client, long imageId){
        try {
            // get channels
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getChannelData(client.getSimpleClient().getCtx(), imageId);
        } catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Channel reading","Could not read image channel on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Channel reading","You don't have the right to read channels on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get the image file format (ex. .lif, .vsi,...)
     *
     * @param client
     * @param imageId
     * @return Image file format
     */
    public static String readImageFileType(OmeroRawClient client, long imageId){
        try {
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
            return imageData.asImage().getFormat().getValue().getValue();
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return "";
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return "";
        }
    }

    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClassData The object class must implement DataObject class
     * @param id
     * @return
     */
    private static DataObject readObject(OmeroRawClient client, String objectClassData, long id){
        try {
            return  client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findObject(client.getSimpleClient().getCtx(), objectClassData, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClassData+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClassData+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
    }


    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClass The object class must implement IObject class
     * @param id
     * @return
     */
    private static IObject readIObject(OmeroRawClient client, String objectClass, long id){
        try {
            return  client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).findIObject(client.getSimpleClient().getCtx(), objectClass, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClass+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClass+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
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
    public static TableData convertMeasurementTableToOmeroTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, OmeroRawClient client, long imageId) {
        return Utils.buildOmeroTableFromMeasurementTable(pathObjects, ob, client, imageId);
    }

    /**
     * Send an OMERO.table to OMERO server and attach it to the image specified by its ID.
     *
     * @param table OMERO.table
     * @param name table name
     * @param client
     * @param imageId
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addTableToOmero(TableData table, String name, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), image, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Send an OMERO.table to OMERO server and attach it to the specified container
     *
     * @param table OMERO.table
     * @param name table name
     * @param client
     * @param container
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static TableData addTableToOmero(TableData table, String name, OmeroRawClient client, DataObject container) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), container, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for "
                    + container.getClass().getName()+" id " +container.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return null;
    }

    /**
     * Send an attachment to OMERO server and attached it to an image specified by its ID.
     *
     * @param file
     * @param client
     * @param imageId
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId) {
        return addAttachmentToOmero(file, client, imageId, null,"");
    }

    /**
     *  Send an attachment to OMERO server and attached it to an image specified by its ID.
     *  You can specify the mimetype of the file.
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype) {
        return addAttachmentToOmero(file, client, imageId, miemtype,"");
    }

    /**
     * Send an attachment to OMERO server and attached it to an image specified by its ID, specifying the mimetype and
     * a description of what the file is and how it works.
     *
     * @param file
     * @param client
     * @param imageId
     * @param miemtype
     * @param description
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype, String description) {
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), image).get();

        } catch (ExecutionException | DSOutOfServiceException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("File Saving","You don't have the right to save a file on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Send an attachment to OMERO server and attached it in the specified container.
     *
     * @param file
     * @param client
     * @param obj
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj) {
        return addAttachmentToOmero(file, client, obj, null,"");
    }

    /**
     *  Send an attachment to OMERO server and attached it in the specified container.
     *  You can specify the mimetype of the file.
     *
     * @param file
     * @param client
     * @param obj
     * @param miemtype
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype) {
        return addAttachmentToOmero(file, client, obj, miemtype,"");
    }

    /**
     * Send an attachment to OMERO server and attached it in the specified container., specifying the mimetype and
     * a description of what the file is and how it works.
     *
     * @param file
     * @param client
     * @param obj
     * @param miemtype
     * @param description
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     */
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype, String description) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), obj).get();

        } catch (ExecutionException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * Link an existing annotation of any type an OMERO object. The annotation should already
     *
     * @param client
     * @param annotationData
     * @param obj
     * @return
     */
    static AnnotationData linkAnnotationToOmero(OmeroRawClient client, AnnotationData annotationData, DataObject obj) {
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), annotationData, obj);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Link Annotation","Error during linking the annotation on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("Link Annotation","You don't have the right to link objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Update a list of OMERO objects
     *
     * @param client
     * @param objects
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateObjectsOnOmero(OmeroRawClient client, List<IObject> objects){
       boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getSimpleClient().getCtx(), objects, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update objects","Error during updating objects on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update objects","You don't have the right to update objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Update an OMERO object.
     *
     * @param client
     * @param object
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateObjectOnOmero(OmeroRawClient client, IObject object){
        boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObject(client.getSimpleClient().getCtx(), object, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update object","Error during updating object on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update object","You don't have the right to update object on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Update the thumbnail of an OMERO image, specified by its id, and given the ID of the updated RenderingDef object linked to that image.
     * <br> <br>
     * Be careful : the image should already have an OMERO ID.
     *
     * @param client
     * @param imageId
     * @param objectId
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateOmeroThumbnail(OmeroRawClient client, long imageId, long objectId){
        boolean wasAdded = true;

        // get the current image
        ImageData image = readOmeroImage(client, imageId);

        // get OMERO thumbnail store
        ThumbnailStorePrx store = null;
        try {
            store = client.getSimpleClient().getGateway().getThumbnailService(client.getSimpleClient().getCtx());
        } catch(DSOutOfServiceException e){
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
           return false;
        }

        if(store == null){
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot get the Thumbnail service for image " + imageId);
            return false;
        }

        try {
            // get the pixel id to retrieve the correct thumbnail
            long pixelId = image.getDefaultPixels().getId();
            // get current thumbnail
            store.setPixelsId(pixelId);
            //set the new settings
            store.setRenderingDefId(objectId);

            try {
                // update the thumbnail
                store.createThumbnails();
            } catch (ServerError e) {
                logger.error("Error during thumbnail creation but thumbnail is updated ");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            }

        } catch (NullPointerException | ServerError e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Thumbnail cannot be updated for image " + imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }

        try {
            // close the store
            store.close();
        } catch (ServerError e) {
            Dialogs.showErrorNotification("Update OMERO Thumbnail", "Cannot close the ThumbnailStore");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }

        return wasAdded;
    }

    /**
     * Download an image from OMERO in the path given in argument.
     *
     * @param client
     * @param imageId
     * @param path
     * @return Downloading status (True if downloaded ; false with error message otherwise)
     */
    public static boolean downloadImage(OmeroRawClient client, long imageId, String path){
        boolean wasDownloaded = true;
        try {
            if(new File(path).exists())
                client.getSimpleClient().getGateway().getFacility(TransferFacility.class).downloadImage(client.getSimpleClient().getCtx(), path, imageId);
            else {
                Dialogs.showErrorNotification("Download object","The following path does not exists : "+path);
                wasDownloaded = false;
            }
        } catch(DSOutOfServiceException | ExecutionException e){
            Dialogs.showErrorNotification("Download object","Error during downloading image "+imageId+" from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDownloaded = false;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Download object","You don't have the right to download image "+imageId+" from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDownloaded = false;
        }

        return wasDownloaded;
    }

    /**
     * Upload an image to a specific dataset on OMERO
     *
     * @param client
     * @param datasetId
     * @param path
     * @return id of the newly uploaded image
     */
    public static List<Long> uploadImage(OmeroRawClient client, long datasetId, String path){
        DatasetWrapper dataset;
        try {
            dataset = client.getSimpleClient().getDataset(datasetId);
            return uploadImage(client, dataset, path);
        }catch(DSAccessException | ServiceException | ExecutionException e) {
            Dialogs.showErrorNotification("Upload image", "The dataset "+datasetId+" does not exist");
            return Collections.emptyList();
        }
    }


    /**
     * Upload an image to a specific dataset on OMERO
     * Code taken from simple-omero-client project from Pierre Pouchin (GreD-Clermont)
     *
     * @param client
     * @param dataset
     * @param path
     * @return id of the newly uploaded image
     */
    public static List<Long> uploadImage(OmeroRawClient client, DatasetWrapper dataset, String path){
        if(dataset == null){
            Dialogs.showErrorNotification("Upload image", "The dataset you want to access does not exist");
            return Collections.emptyList();
        }

        ImportConfig config = new ImportConfig();
        config.target.set("Dataset:" + dataset.getId()); // can also import an image into a well or wellsample => to check
        config.username.set(client.getUsername());
        config.email.set(client.getLoggedInUser().getEmail());

        Collection<Pixels> pixels = new ArrayList<>(1);
        OMEROMetadataStoreClient store = null;
        try (OMEROWrapper reader = new OMEROWrapper(config)) {
            store = client.getSimpleClient().getGateway().getImportStore(client.getSimpleClient().getCtx());
            store.logVersionInfo(config.getIniVersionNumber());
            reader.setMetadataOptions(new DefaultMetadataOptions(MetadataLevel.ALL));

            ImportLibrary library = new ImportLibrary(store, reader);
            library.addObserver(new LoggingImportMonitor());

            ErrorHandler handler = new ErrorHandler(config);

            ImportCandidates candidates = new ImportCandidates(reader, new String[]{path}, handler);
            ExecutorService uploadThreadPool = Executors.newFixedThreadPool(config.parallelUpload.get());

            List<ImportContainer> containers = candidates.getContainers();
            if (containers != null) {
                for (int i = 0; i < containers.size(); i++) {
                    ImportContainer container = containers.get(i);
                    container.setTarget(dataset.asDataObject().asIObject());
                    List<Pixels> imported = library.importImage(container, uploadThreadPool, i);
                    pixels.addAll(imported);
                }
            }
            uploadThreadPool.shutdown();
        } catch (Throwable e) {
            Dialogs.showErrorNotification("Upload image","Error during uploading image "+path+" to OMERO.");
            logger.error(String.valueOf(e));
        } finally {
            if(store != null)
                store.logout();
        }

        List<Long> ids = new ArrayList<>(pixels.size());
        pixels.forEach(pix -> ids.add(pix.getImage().getId().getValue()));
        return ids.stream().distinct().collect(Collectors.toList());
    }

    /**
     * Convert a QuPath measurement table into a CSV file,
     * including the OMERO image ID on which the measurements are referring to.
     *
     * @param pathObjects
     * @param ob
     * @param imageId
     * @param name file name
     * @param path where to save the newly created CSV file.
     * @return CSV file of measurement table.
     */
    public static File buildCSVFileFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob, long imageId, String name, String path) {
        return Utils.buildCSVFileFromMeasurementTable(pathObjects, ob, imageId, name);
    }


    /**
     * Delete the specified ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param roisToDelete
     */
    public static void deleteROIs(OmeroRawClient client, Collection<ROIWrapper> roisToDelete)
            throws AccessException, ServiceException, OMEROServerError, ExecutionException, InterruptedException {
        if(!(roisToDelete.isEmpty()))
            client.getSimpleClient().delete(roisToDelete);
    }


    /**
     * Send ROIs to OMERO server and attached them to the specified image.
     *
     * @param client
     * @param imageId
     * @param omeroRois
     * @return Sending status (True if sent ; false with error message otherwise)
     */
    public static List<ROIWrapper> addROIs(OmeroRawClient client, long imageId, List<ROIWrapper> omeroRois)
            throws AccessException, ServiceException, ExecutionException {

        List<ROIWrapper> uploaded = new ArrayList<>();
        if (!(omeroRois.isEmpty())) {
            ImageWrapper imageWrapper = client.getSimpleClient().getImage(imageId);
            uploaded = imageWrapper.saveROIs(client.getSimpleClient(), omeroRois);
        } else {
            logger.warn("There is no Annotations to upload on OMERO");
        }

        return uploaded;
    }

    /**
     * Read ROIs from OMERO server attached to an image specified by its id.
     *
     * @param client
     * @param imageId
     * @return Image's list of OMERO ROIs
     */
    public static List<ROIWrapper> fetchROIs(OmeroRawClient client, long imageId)
            throws AccessException, ServiceException, ExecutionException {
        return client.getSimpleClient().getImage(imageId).getROIs(client.getSimpleClient());
    }


    /**
     * Splits the "target" map into two parts : one part containing key/values that are referenced in the "reference" map and
     * the other containing remaining key/values that are not referenced in the "reference".
     *
     * @param reference
     * @param target
     * @return List of new kvp and existing kvp maps
     */
    public static List<Map<String, String>> splitNewAndExistingKeyValues(Map<String, String> reference, Map<String, String> target){
        Map<String, String> existingKVP = new HashMap<>();

        // filter key/values that are contained in the reference
        reference.forEach((key, value) -> existingKVP.putAll(target.keySet()
                .stream()
                .filter(f -> f.equals(key))
                .collect(Collectors.toMap(e->key,e->target.get(key)))));

        // filter the new key values
        Map<String,String> updatedKV = target.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        existingKVP.forEach(updatedKV::remove);

        // add the two separate maps to a list.
        List<Map<String, String>> results = new ArrayList<>();
        results.add(existingKVP);
        results.add(updatedKV);

        return results;
    }


    /**
     * Get attachments from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;
        try{
            // read image
            ImageData image = readOmeroImage(client, imageId);

            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), image, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter attachments
        return annotations.stream()
                .filter(FileAnnotationData.class::isInstance)
                .map(FileAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Get attachments from OMERO server attached to the specified image.
     *
     * @param client
     * @param parent
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, DataObject parent) {
        List<AnnotationData> annotations;
        try{
            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), parent, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "Cannot read attachment from "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "You don't have the right to read attachments on OMERO for "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter attachments
        return annotations.stream()
                .filter(FileAnnotationData.class::isInstance)
                .map(FileAnnotationData.class::cast)
                .collect(Collectors.toList());
    }

    /**
     * Get tables from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return Sending status (True if retrieved ; false with error message otherwise)
     */
    public static Collection<FileAnnotationData> readTables(OmeroRawClient client, long imageId) {
        try{
            // read image
            ImageData image = readOmeroImage(client, imageId);

            // get annotations
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).getAvailableTables(client.getSimpleClient().getCtx(), image);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return Collections.emptyList();
    }

    /**
     * Delete given files on OMERO
     *
     * @param client
     * @param data
     */
    public static boolean deleteFiles(OmeroRawClient client, List<FileAnnotationData> data){
        boolean hasBeenDeleted = false;

        try{
            List<IObject> IObjectData = data.stream().map(FileAnnotationData::asIObject).collect(Collectors.toList());
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), IObjectData);
            hasBeenDeleted = true;
        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("File deletion","Could not delete files on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("File deletion", "You don't have the right to delete those files on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
        return hasBeenDeleted;
    }

    /**
     *  Create a new dataset on OMERO.
     *  If the project ID > 0, then the dataset is link to the specified project. Otherwise, the dataset is orphaned
     *  (i.e. to create an orphaned dataset, set the project ID to -1)
     *
     * @param client
     * @param datasetName
     * @param datasetDescription
     * @param projectId
     * @return
     * @throws ExecutionException
     * @throws DSOutOfServiceException
     * @throws DSAccessException
     */
    public static DatasetWrapper createNewDataset(OmeroRawClient client,  String datasetName, String datasetDescription, long projectId)
            throws ExecutionException, DSOutOfServiceException, DSAccessException {

        // create a new dataset
        Dataset dataset = new DatasetI();
        dataset.setName(omero.rtypes.rstring(datasetName));
        dataset.setDescription(omero.rtypes.rstring(datasetDescription));
        IObject objTosave;

        if(projectId > 0) {
            // link the dataset to a project
            ProjectDatasetLink link = new ProjectDatasetLinkI();
            link.setChild(dataset);
            link.setParent(new ProjectI(projectId, false));
            objTosave = link;
        }else{
            objTosave = dataset;
        }

        // send the new dataset to OMERO
        IObject r = client.getSimpleClient().getDm().saveAndReturnObject(client.getSimpleClient().getCtx(), objTosave);
        return client.getSimpleClient().getDataset(r.getId().getValue());
    }

    /**
     * Unlink tags from an image on OMERO
     *
     * @param imageServer
     */
    protected static void unlinkTags(OmeroRawImageServer imageServer){
        try{
            List<TagAnnotationWrapper> tags = imageServer.getImageWrapper().getTags(imageServer.getClient().getSimpleClient());
            List<IObject> oss = new ArrayList<>();
            for(TagAnnotationWrapper tag : tags) {
                oss.addAll(imageServer.getClient().getSimpleClient().findByQuery("select link from ImageAnnotationLink" +
                        " link where link.parent = " + imageServer.getId() +
                        " and link.child = " + tag.getId()));
            }
            imageServer.getClient().getSimpleClient().getDm().delete(imageServer.getClient().getSimpleClient().getCtx(), oss).block(500);

        }catch(ExecutionException | DSOutOfServiceException | ServerError | InterruptedException e) {
            Dialogs.showErrorNotification("Unlink tags", "Cannot unlink tags for the image "+imageServer.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Unlink tags", "You don't have the right to unlink tags for image "+imageServer.getId());
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }


    /**
     * Get the thumbnail of the specified OMERO image.
     * <br> <br>
     * Code copied from Pierre Pouchin from {simple-omero-client} project, {ImageWrapper} class, {getThumbnail} method
     * and adapted for QuPath compatibility.
     *
     * @param client
     * @param imageId
     * @param prefSize
     * @return The image's thumbnail
     */
    public static BufferedImage getThumbnail(OmeroRawClient client, long imageId, int prefSize) {

        // get the current defaultPixel
        PixelsData pixel;
        try {
            pixel = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId).getDefaultPixels();
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException | NullPointerException e){
            Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be read.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return readLocalImage(noImageThumbnail);
        }

        // set the thumbnail size
        int   sizeX  = pixel.getSizeX();
        int   sizeY  = pixel.getSizeY();
        float ratioX = (float) sizeX / prefSize;
        float ratioY = (float) sizeY / prefSize;
        float ratio  = Math.max(ratioX, ratioY);
        int   width  = (int) (sizeX / ratio);
        int   height = (int) (sizeY / ratio);

        // get rendering settings for the current image
        RenderingDef renderingSettings = readOmeroRenderingSettings(client, imageId);

        // check if we can access to rendering settings
        if(renderingSettings == null) {
            Dialogs.showErrorNotification("Channel settings", "Cannot access to rendering settings of the image " + imageId);
            return readLocalImage(noImageThumbnail);
        }

        // get thumbnail
        byte[] array;
        ThumbnailStorePrx store = null;
        try {
            store = client.getSimpleClient().getGateway().getThumbnailService(client.getSimpleClient().getCtx());
            store.setPixelsId(pixel.getId());
            store.setRenderingDefId(renderingSettings.getId().getValue());
            array = store.getThumbnail(rint(width), rint(height));
        } catch (DSOutOfServiceException | ServerError | NullPointerException e) {
            Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be read.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return readLocalImage(noImageThumbnail);
        } finally {
            if(store != null){
                try{
                    store.close();
                } catch (ServerError s) {

                }
            }
        }

        // convert thumbnail into BufferedImage
        if (array != null) {
            try (ByteArrayInputStream stream = new ByteArrayInputStream(array)) {
                //Create a buffered image to display
                BufferedImage thumbnail = ImageIO.read(stream);
                if(thumbnail == null)
                    return readLocalImage(noImageThumbnail);
                else return thumbnail;
            }catch(IOException e){
                Dialogs.showErrorNotification( "Thumbnail reading","The thumbnail of image "+imageId+" cannot be converted to buffered image.");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
                return readLocalImage(noImageThumbnail);
            }
        }
        else return readLocalImage(noImageThumbnail);
    }


    /**
     * read an image stored in the resource folder of the main class
     *
     * @param imageName
     * @return The read image or null if cannot be read
     */
    public static BufferedImage readLocalImage(String imageName){
        try {
            return ImageIO.read(OmeroRawTools.class.getClassLoader().getResource("images/"+imageName));
        }catch(IOException e){
            return new BufferedImage(256,256, BufferedImage.TYPE_BYTE_GRAY);
        }
    }


    /**
     * Return a clean URI of the server from which the given URI is specified. This method relies on
     * the specified {@code uri} to be formed properly (with at least a scheme and a host).
     * <p>
     * A few notes:
     * <ul>
     * <li> If the URI does not contain a host (but does a path), it will be returned without modification. </li>
     * <li> If no host <b>and</b> no path is found, {@code null} is returned. </li>
     * <li> If the specified {@code uri} does not contain a scheme, {@code https://} will be used. </li>
     * </ul>
     * <p>
     * E.g. {@code https://www.my-server.com/show=image-462} returns {@code https://www.my-server.com/}
     *
     * @param uri
     * @return clean uri
     */
    public static URI getServerURI(URI uri) {
        if (uri == null)
            return null;

        try {
            var host = uri.getHost();
            var path = uri.getPath();
            if (host == null || host.isEmpty())
                return (path == null || path.isEmpty()) ? null : uri;

            var scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty())
                scheme = "https://";
            return new URL(scheme, host, uri.getPort(), "").toURI();
        } catch (MalformedURLException | URISyntaxException ex) {
            logger.error("Could not parse server from {}: {}", uri, ex.getLocalizedMessage());
        }
        return null;
    }


    /**
     * OMERO requests that return a list of items are paginated
     * (see <a href="https://docs.openmicroscopy.org/omero/5.6.1/developers/json-api.html#pagination">OMERO API docs</a>).
     * Using this helper method ensures that all the requested data is retrieved.
     *
     * @param url
     * @return list of {@code Json Element}s
     * @throws IOException
     */
    // TODO: Consider using parallel/asynchronous requests
    static List<JsonElement> readPaginated(URL url) throws IOException {
        List<JsonElement> jsonList = new ArrayList<>();
        String symbol = (url.getQuery() != null && !url.getQuery().isEmpty()) ? "&" : "?";

        // Open connection
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int response = connection.getResponseCode();

        // Catch bad response
        if (response != 200)
            return jsonList;

        JsonObject map;
        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
            map = GsonTools.getInstance().fromJson(reader, JsonObject.class);
        }

        map.get("data").getAsJsonArray().forEach(jsonList::add);
        JsonObject meta = map.getAsJsonObject("meta");
        int offset = 0;
        int totalCount = meta.get("totalCount").getAsInt();
        int limit = meta.get("limit").getAsInt();
        while (offset + limit < totalCount) {
            offset += limit;
            URL nextURL = new URL(url + symbol + "offset=" + offset);
            InputStreamReader newPageReader = new InputStreamReader(nextURL.openStream());
            JsonObject newPageMap = GsonTools.getInstance().fromJson(newPageReader, JsonObject.class);
            newPageMap.get("data").getAsJsonArray().forEach(jsonList::add);
        }
        return jsonList;
    }




    public static String getErrorStackTraceAsString(Exception e){
        return Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).reduce("",(a, b)->a + "     at "+b+"\n");
    }

    /**
     * @return formatted date
     */
    public static String getCurrentDateAndHour(){
        LocalDateTime localDateTime = LocalDateTime.now();
        LocalTime localTime = localDateTime.toLocalTime();
        LocalDate localDate = localDateTime.toLocalDate();
        return String.valueOf(localDate.getYear())+
                (localDate.getMonthValue() < 10 ? "0"+localDate.getMonthValue():localDate.getMonthValue()) +
                (localDate.getDayOfMonth() < 10 ? "0"+localDate.getDayOfMonth():localDate.getDayOfMonth())+"-"+
                (localTime.getHour() < 10 ? "0"+localTime.getHour():localTime.getHour())+"h"+
                (localTime.getMinute() < 10 ? "0"+localTime.getMinute():localTime.getMinute())+"m"+
                (localTime.getSecond() < 10 ? "0"+localTime.getSecond():localTime.getSecond());

    }

    /*
     *
     *
     *                                           Deprecated methods
     *
     *
     */

    /**
     * Get annotations (i.e. tag, key-value, comment...) attached to an image on OMERO, specified by its id.
     *
     * @param client
     * @param obj
     * @return List of annotation objects
     * @deprecated use  {@link GenericRepositoryObjectWrapper#getAnnotations(Client)} instead
     */
    @Deprecated
    public static List<AnnotationData> readOmeroAnnotations(OmeroRawClient client, DataObject obj){
        try {
            // read annotations linked to the image
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), obj);

        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO wells corresponding to the plate id
     *
     * @param client
     * @param plateId
     * @return List of OMERO dataset objects
     * @deprecated use {@link fr.igred.omero.repository.PlateWrapper#getWells(Client)}
     */
    @Deprecated
    public static Collection<WellData> readOmeroWells(OmeroRawClient client, long plateId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getWells(client.getSimpleClient().getCtx(), plateId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading wells","You don't have the right to access wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get user's orphaned images from the OMERO server
     *
     * @param client
     * @param userId
     * @return List of orphaned images
     * @deprecated use {@link OmeroRawTools#readOrphanedImages(OmeroRawClient, ExperimenterWrapper) instead}
     */
    @Deprecated
    public static Collection<ImageData> readOrphanedImages(OmeroRawClient client, long userId) {
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getSimpleClient().getCtx(), userId);
        } catch (ExecutionException e) {
            Dialogs.showErrorMessage("Orphaned images","Cannot retrieved orphaned images for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all orphaned datasets from the OMERO server linked to the current group (contained in the security context of the current client).
     *
     * @param client
     * @return List of orphaned datasets
     * @deprecated user {@link OmeroRawTools#readOrphanedDatasets(OmeroRawClient, ExperimenterWrapper)}
     */
    @Deprecated
    public static Collection<DatasetData> readOmeroOrphanedDatasets(OmeroRawClient client)  {
        Collection<DatasetData> orphanedDatasets;

        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAllByQuery("select dataset from Dataset as dataset " +
                    "left outer join fetch dataset.details.owner " +
                    "where not exists (select obl from " +
                    "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            orphanedDatasets = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        return orphanedDatasets;
    }

    /**
     * Get user's orphaned datasets from the OMERO server
     *
     * @param client the client {@link OmeroRawClient} object
     * @param userId
     * @return List orphaned of datasets
     * @deprecated user {@link OmeroRawTools#readOrphanedDatasets(OmeroRawClient, ExperimenterWrapper)}
     */
    @Deprecated
    public static Collection<DatasetData> readOrphanedDatasets(OmeroRawClient client, long userId) {
        try {
            // query orphaned dataset
            List<IObject> datasetObjects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAllByQuery("select dataset from Dataset as dataset " +
                    "join fetch dataset.details.owner as o " +
                    "where o.id = "+ userId +
                    "and not exists (select obl from " +
                    "ProjectDatasetLink as obl where obl.child = dataset.id) ", null);

            // get orphaned dataset ids
            List<Long> datasetIds = datasetObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .collect(Collectors.toList());

            // get orphaned datasets
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);

        } catch (DSOutOfServiceException | ExecutionException | ServerError e) {
            Dialogs.showErrorMessage("Orphaned datasets","Cannot retrieved orphaned datasets for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset of the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO projects corresponding to the list of ids
     *
     * @param client
     * @param projectIds
     * @return List of OMERO project objects
     * @deprecated use {@link fr.igred.omero.Client#getProjects(Long...)} instead
     */
    @Deprecated
    public static Collection<ProjectData> readOmeroProjects(OmeroRawClient client, List<Long> projectIds){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), projectIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects","An error occurs when reading OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects","You don't have the right to access OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }


    /**
     * Get all OMERO projects linked to the specified user.
     *
     * @param client
     * @param userId
     * @return User's list of OMERO project objects
     * @deprecated Use {@link fr.igred.omero.Client#getProjects(ExperimenterWrapper)} instead
     */
    @Deprecated
    public static Collection<ProjectData> readOmeroProjectsByUser(OmeroRawClient client, long userId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO screens linked to the specified user.
     *
     * @param client
     * @param userId
     * @return User's list of OMERO project objects
     * @deprecated Use {@link fr.igred.omero.Client#getScreens(ExperimenterWrapper)} instead
     */
    @Deprecated
    public static Collection<ScreenData> readOmeroScreensByUser(OmeroRawClient client, long userId){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getScreens(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO datasets corresponding to the list of ids
     *
     * @param client
     * @param datasetIds
     * @return List of OMERO dataset objects
     * @deprecated use {@link fr.igred.omero.Client#getDatasets(Long...)}
     */
    @Deprecated
    public static Collection<DatasetData> readOmeroDatasets(OmeroRawClient client, List<Long> datasetIds){
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading datasets","You don't have the right to access OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all OMERO datasets corresponding to the list of ids
     *
     * @param client
     * @param plateIds
     * @return List of OMERO dataset objects
     * @deprecated use {@link OmeroRawTools#readPlates(OmeroRawClient, List)} instead
     */
    @Deprecated
    public static Collection<PlateData> readOmeroPlates(OmeroRawClient client, List<Long> plateIds){
        return readPlates(client, plateIds).stream().map(PlateWrapper::asDataObject).collect(Collectors.toList());
    }


    /**
     * Delete all existing ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param imageId
     * @deprecated use {@link OmeroRawTools#deleteROIs(OmeroRawClient, Collection)} instead
     */
    @Deprecated
    public static void deleteAllOmeroROIs(OmeroRawClient client, long imageId) {
        try {
            // extract ROIData
            List<IObject> roiData = fetchROIs(client, imageId).stream().map(ROIWrapper::asDataObject).map(ROIData::asIObject).collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete of cannot delete them");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete ROIs on OMERO on the image  " + imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }

    /**
     * Delete the specified ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param roisToDelete
     * @deprecated use {@link OmeroRawTools#deleteROIs(OmeroRawClient, Collection)} e} instead
     */
    @Deprecated
    public static void deleteOmeroROIs(OmeroRawClient client, Collection<ROIData> roisToDelete) {
        try {
            // Convert to IObject
            List<IObject> roiData = roisToDelete.stream().map(ROIData::asIObject).collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete those ROIs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
        }
    }

    /**
     * Send ROIs to OMERO server and attached them to the specified image.
     *
     * @param client
     * @param imageId
     * @param omeroRois
     * @return Sending status (True if sent ; false with error message otherwise)
     * @deprecated use {@link OmeroRawTools#addROIs(OmeroRawClient, long, List)} instead
     */
    @Deprecated
    public static boolean writeOmeroROIs(OmeroRawClient client, long imageId, List<ROIData> omeroRois) {
        boolean roiSaved = false;

        // import ROIs on OMERO
        if (!(omeroRois.isEmpty())) {
            try {
                // save ROIs
                client.getSimpleClient().getGateway().getFacility(ROIFacility.class).saveROIs(client.getSimpleClient().getCtx(), imageId, client.getSimpleClient().getGateway().getLoggedInUser().getId(), omeroRois);
                roiSaved = true;
            } catch (ExecutionException | DSOutOfServiceException e){
                Dialogs.showErrorNotification("ROI Saving","Error during saving ROIs on OMERO.");
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            } catch (DSAccessException e){
                Dialogs.showErrorNotification("ROI Saving","You don't have the right to write ROIs from OMERO on the image "+imageId);
                logger.error(String.valueOf(e));
                logger.error(getErrorStackTraceAsString(e));
            }
        } else {
            Dialogs.showInfoNotification("Upload annotations","There is no Annotations to upload on OMERO");
        }

        return roiSaved;
    }

    /**
     * Convert QuPath pathObjects into OMERO ROIs.
     *
     * @param pathObjects
     * @return List of OMERO ROIs
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static List<ROIWrapper> createOmeroROIsFromPathObjects(Collection<PathObject> pathObjects){
        return OmeroRawShapes.createOmeroROIsFromPathObjects(pathObjects);
    }

    /**
     * Convert OMERO ROIs into QuPath pathObjects
     *
     * @param roiWrapperList
     * @return List of QuPath pathObjects
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static Collection<PathObject> createPathObjectsFromOmeroROIs(List<ROIWrapper> roiWrapperList){
        return OmeroRawShapes.createPathObjectsFromOmeroROIs(roiWrapperList);
    }

    /**
     * Read the comment attached to one shape of an OMERO ROI.
     *
     * @param shape
     * @return The shape comment
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static String getROIComment(Shape shape){
        return OmeroRawShapes.getROIComment(shape);
    }

    /**
     * Read the comments attach to an OMERO ROI (i.e. read each comment attached to each shape of the ROI)
     *
     * @param roiData
     * @return List of comments
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static List<String> getROIComment(ROIData roiData) {
        return OmeroRawShapes.getROIComment(new ROIWrapper(roiData));
    }

    /**
     * Parse the comment based on the format introduced in {OmeroRawShapes.setRoiComment(PathObject src, String objectID, String parentID)}
     *
     * @param comment
     * @return The split comment
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static String[] parseROIComment(String comment) {
        return OmeroRawShapes.parseROIComment(comment);
    }



    /**
     * Update specified key value pairs on OMERO server.
     *
     * @param keyValuePairs
     * @param client
     * @return Updating status (True if updated ; false with error message otherwise)
     * @deprecated method removed
     */
    @Deprecated
    public static boolean updateKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasUpdated = true;
        try {
            // update key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()),null);
        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("OMERO KeyValues update", "Cannot update existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to update key value pairs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasUpdated = false;
        }
        return wasUpdated;
    }

    /**
     * Delete specified key value pairs on OMERO server
     *
     * @param keyValuePairs
     * @param client
     * @return Deleting status (True if deleted ; false with error message otherwise)
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static boolean deleteKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        boolean wasDeleted = true;
        try {
            // remove current key-values
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()));
        } catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("OMERO KeyValues deletion", "Cannot delete existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasDeleted = false;
        }
        return wasDeleted;
    }



    /**
     * Try to solve an error in OMERO regarding the keys creation.
     * On OMERO, it is possible to have two identical keys with a different value. This should normally never append.
     * This method checks if all keys are unique and output false if there is at least two identical keys.
     *
     * @param keyValues
     * @return Check status (True if all keys unique ; false otherwise)
     * @deprecated method moved with a non-public access
     */
    @Deprecated
    public static boolean checkUniqueKeyInAnnotationMap(List<NamedValue> keyValues){
        return Utils.checkUniqueKeyInAnnotationMap(keyValues);
    }

    /**
     * Send key value pairs on OMERO and attach them to the specified image.
     *
     * @param keyValuePairs
     * @param client
     * @param imageId
     * @return Sending status (True if sent ; false with error message otherwise)
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper)} instead
     */
    @Deprecated
    public static boolean addKeyValuesOnOmero(MapAnnotationData keyValuePairs, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageWrapper imageWrapper = client.getSimpleClient().getImage(imageId);

            // send key-values to OMERO
            imageWrapper.link(client.getSimpleClient(), new MapAnnotationWrapper(keyValuePairs));
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "Cannot add new key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to add some key value pairs on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }

    /**
     * Read key value pairs from OMERO server and convert them into NamedValue OMERO-compatible-objects.
     *
     * @param client
     * @param imageId
     * @return List of NamedValue objects.
     * @deprecated use {@link MapAnnotationWrapper#getContent()} instead
     */
    @Deprecated
    public static List<NamedValue> readKeyValuesAsNamedValue(OmeroRawClient client, long imageId) {
        return readKeyValues(client, imageId).stream()
                .flatMap(e->((List<NamedValue>)(e.getContent())).stream())
                .collect(Collectors.toList());
    }

    /**
     * Get key-value pairs from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return List of Key-Value pairs as annotation objects
     * @deprecated use {@link ImageWrapper#getMapAnnotations(Client)} instead
     */
    @Deprecated
    public static List<MapAnnotationData> readKeyValues(OmeroRawClient client, long imageId) {
        List<MapAnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageWrapper imageWrapper = client.getSimpleClient().getImage(imageId);
            // read annotations linked to the image
            List<MapAnnotationWrapper> annotationWrappers = imageWrapper.getMapAnnotations(client.getSimpleClient());
            annotations = annotationWrappers.stream().map(MapAnnotationWrapper::asDataObject).collect(Collectors.toList());
        }catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO key value pairs", "Cannot get key values from OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter key values
        return annotations;
    }



    /**
     * Read tags from OMERO server, attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return List of Tag objects attached to the image
     * @deprecated use {@link GenericRepositoryObjectWrapper#getTags(Client)} instead
     */
    @Deprecated
    public static List<TagAnnotationData> readTags(OmeroRawClient client, long imageId) {
        List<AnnotationData> annotations;

        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // read annotations linked to the image
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO Tags", "Cannot get tags from OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "You don't have the right to read tags from OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter tags
        return annotations.stream()
                .filter(TagAnnotationData.class::isInstance)
                .map(TagAnnotationData.class::cast)
                .collect(Collectors.toList());
    }


    /**
     * Read all tags available for the logged-in user
     *
     * @param client
     * @return List of available tag objects
     * @deprecated use {@link Client#getTags()} instead
     */
    @Deprecated
    public static List<TagAnnotationData> readUserTags(OmeroRawClient client) {
        List<IObject> objects;

        try {
            // get current image from OMERO
            objects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAll(TagAnnotation.class.getSimpleName(),null);

        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "Error getting all available tags");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }

        // filter tags
        return objects.stream()
                .map(TagAnnotation.class::cast)
                .map(TagAnnotationData::new)
                .collect(Collectors.toList());
    }

    /**
     *  Send a new tag on OMERO server and attach it to the specified image.
     *
     * @param tags
     * @param client
     * @param imageId
     * @return Sending status (True if sent ; false with error message otherwise)
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper)} instead
     */
    @Deprecated
    public static boolean addTagsOnOmero(TagAnnotationData tags, OmeroRawClient client, long imageId) {
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // send key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), tags, imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "Cannot add new tags on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "You don't have the right to add tags on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * create a new dataset on OMERO and add a project as parent object
     *
     * @param client
     * @param projectId
     * @param datasetName
     * @param datasetDescription
     * @return OMERO dataset
     *
     * @deprecated use {@link OmeroRawTools#createNewDataset(OmeroRawClient, String, String, long)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName, String datasetDescription) {
        try {
            return createNewDataset(client, datasetName, datasetDescription, projectId).asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName+" in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * create a new orphaned dataset on OMERO
     *
     * @param client
     * @param datasetName
     * @param datasetDescription
     * @return OMERO dataset
     * @deprecated use {@link OmeroRawTools#createNewDataset(OmeroRawClient, String, String, long)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName, String datasetDescription){
        try {
            return createNewDataset(client, datasetName, datasetDescription, -1).asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * create a new orphaned dataset on OMERO
     *
     * @param client
     * @param datasetName
     * @return OMERO dataset
     * @deprecated use {@link OmeroRawTools#createNewDataset(OmeroRawClient, String, String, long)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName){
        try {
            return createNewDataset(client, datasetName, "", -1).asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * create a new dataset on OMERO and add a project as parent object
     *
     * @param client
     * @param projectId
     * @param datasetName
     * @return OMERO dataset
     * @deprecated use {@link OmeroRawTools#createNewDataset(OmeroRawClient, String, String, long)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName){
        try {
            return createNewDataset(client, datasetName, "", projectId).asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Get OMERO corresponding to the id
     *
     * @param client
     * @param datasetId
     * @return OMERO dataset or null object is ot doesn't exists
     * @deprecated use {@link Client#getDataset(Long)} instead
     */
    @Deprecated
    public static DatasetData readOmeroDataset(OmeroRawClient client, Long datasetId){
        Collection<DatasetData> datasets = readOmeroDatasets(client, Collections.singletonList(datasetId));
        if(datasets.isEmpty())
            return null;
        return datasets.iterator().next();

    }

}