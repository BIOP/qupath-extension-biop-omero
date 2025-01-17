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
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.GenericAnnotationWrapper;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import fr.igred.omero.repository.PlateWrapper;
import fr.igred.omero.repository.ProjectWrapper;
import fr.igred.omero.repository.ScreenWrapper;
import fr.igred.omero.repository.WellWrapper;
import fr.igred.omero.roi.ROIWrapper;

import omero.RLong;
import omero.ServerError;
import omero.api.IQueryPrx;
import omero.api.RenderingEnginePrx;
import omero.api.ThumbnailStorePrx;
import omero.gateway.Gateway;
import omero.gateway.SecurityContext;
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
import omero.gateway.model.PlateData;
import omero.gateway.model.ProjectData;
import omero.gateway.model.ROIData;
import omero.gateway.model.ScreenData;
import omero.gateway.model.TableData;
import omero.gateway.model.TagAnnotationData;
import omero.gateway.model.WellData;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import omero.model.IObject;
import omero.model.NamedValue;
import omero.model.PlateI;
import omero.model.RenderingDef;
import omero.model.Shape;
import omero.model.TagAnnotation;
import omero.model.WellSample;
import omero.sys.ParametersI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.biop.servers.omero.raw.OmeroRawImageServer;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.common.GeneralTools;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.common.LogTools;
import qupath.lib.gui.measure.ObservableMeasurementTableData;
import qupath.lib.objects.PathObject;

import javax.imageio.ImageIO;


/**
 * Static helper methods related to OMERORawImageServer.
 *
 * @author Rémy Dornier
 *
 */
public class OmeroRawTools {

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
     * get the group of which an image is part of. This method is particularly helpful for admins to deal
     * with images that are not in the default group.
     *
     * @param client the client that handles the OMERO connection
     * @param imageId the id of the image to retrieve
     * @return The group id, -1 if the image cannot be fetched.
     */
    public static long getGroupIdFromImageId(OmeroRawClient client, long imageId){
        try {
            // request an imageData object for the image id by searching in all groups on OMERO
            // take care if the user is admin or not
            ImageData img = (ImageData) client.getSimpleClient().getBrowseFacility().findObject(client.getSimpleClient().getCtx(), "ImageData", imageId, true);
            return img.getGroupId();

        } catch (DSOutOfServiceException | NoSuchElementException | ExecutionException | DSAccessException e) {
            Utils.errorLog(logger,"OMERO Group", "Cannot retrieved group id from image "+imageId, e,false);
            return -1;
        }
    }

    /**
     * fetch parents container of OMERO containers
     *
     * @param client the client that handles the OMERO connection
     * @param container child image or container
     * @param qpNotif true to display a QuPath notification
     * @return List of Image's, Dataset's, Well's or Plate's parent(s) ; empty list otherwise
     *
     * @throws ServiceException   Cannot connect to OMERO.
     * @throws AccessException    Data cannot be accessed
     * @throws OMEROServerError   An error occurred server side
     * @throws ExecutionException The result of a task cannot be retrieved
     */
    public static List<? extends GenericRepositoryObjectWrapper<?>> getParentContainer(OmeroRawClient client, GenericRepositoryObjectWrapper<?> container, boolean qpNotif)
            throws ServiceException, OMEROServerError, AccessException, ExecutionException {
        long id = container.getId();

        if(container instanceof ImageWrapper) {
            // get the parent datasets
            List<IObject> datasetObjects = client.getSimpleClient()
                    .findByQuery("select link.parent from DatasetImageLink as link where link.child=" + id);

            if (!datasetObjects.isEmpty()) {
                logger.info("The current image " + id + " has a dataset as parent");
                // get projects' id
                Long[] ids = datasetObjects.stream()
                        .map(IObject::getId)
                        .map(RLong::getValue)
                        .distinct()
                        .toArray(Long[]::new);

                return client.getSimpleClient().getDatasets(ids);
            } else {
                logger.info("The current image " + id + " has a well as parent");

                List<IObject> wellSamplesObjects = client.getSimpleClient().findByQuery("select ws from WellSample ws where image=" + id);

                Long[] ids = wellSamplesObjects.stream()
                        .map(WellSample.class::cast)
                        .map(WellSample::getWell)
                        .map(IObject::getId)
                        .map(RLong::getValue)
                        .toArray(Long[]::new);

                if (ids.length != 0)
                    return client.getSimpleClient().getWells(ids);
                else {
                    Utils.errorLog(logger, "OMERO parent container", "The current image " + id + " has no parent.", qpNotif);
                    return Collections.emptyList();
                }
            }

        }else if (container instanceof DatasetWrapper) {
            // get the parent projects
            List<IObject> projectObjects = client.getSimpleClient().findByQuery("select link.parent from ProjectDatasetLink as link " +
                    "where link.child=" + id);

            // get projects' id
            Long[] projectIds = projectObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .distinct()
                    .toArray(Long[]::new);

            return client.getSimpleClient().getProjects(projectIds);

        }else if (container instanceof WellWrapper) {
            return Collections.singletonList(client.getSimpleClient().getWell(id).getPlate());
        }else if (container instanceof PlateWrapper) {
            // get parent screen
            List<IObject> screenObjects = client.getSimpleClient().findByQuery("select link.parent from ScreenPlateLink as link " +
                    "where link.child=" + id);

            // get screens' id
            Long[] screenIds = screenObjects.stream()
                    .map(IObject::getId)
                    .map(RLong::getValue)
                    .distinct()
                    .toArray(Long[]::new);

            return client.getSimpleClient().getScreens(screenIds);

        }else if (container instanceof ProjectWrapper || container instanceof ScreenWrapper) {
            Utils.warnLog(logger, "OMERO parent container", "No parent for " + container.getClass().getSimpleName() + " id " + id, qpNotif);
        }else{
            Utils.warnLog(logger, "OMERO parent container","Unsupported object : "+container.getClass().getSimpleName()+" id "+id, qpNotif);
        }
        return Collections.emptyList();
    }

    /**
     * get the parents of an OMERO object (from dataset/well to screen/project)
     *
     * @param imageServer current QuPath entry
     * @param obj OMERO object to read the hierarchy from
     * @param qpNotif true to display a QuPath notification
     * @return a map of the parent containers name and id
     */
    protected static Map<String,String> getParentHierarchy(OmeroRawImageServer imageServer, GenericRepositoryObjectWrapper<?> obj, boolean qpNotif)
            throws AccessException, ServiceException, OMEROServerError, ExecutionException {
        Map<String,String> containers = new HashMap<>();

        if (obj instanceof ScreenWrapper) {
            containers.put("screen-name", obj.getName());
            containers.put("screen-id", String.valueOf(obj.getId()));
        } else if (obj instanceof ProjectWrapper) {
            containers.put("project-name", obj.getName());
            containers.put("project-id", String.valueOf(obj.getId()));
        } else if (obj instanceof DatasetWrapper) {
            containers.put("dataset-name", obj.getName());
            containers.put("dataset-id", String.valueOf(obj.getId()));
            List<? extends GenericRepositoryObjectWrapper<?>> parentList = OmeroRawTools.getParentContainer(imageServer.getClient(), obj, qpNotif);
            if(!parentList.isEmpty())
                containers.putAll(getParentHierarchy(imageServer,parentList.get(0), qpNotif));
        } else if (obj instanceof PlateWrapper) {
            containers.put("plate-name", obj.getName());
            containers.put("plate-id", String.valueOf(obj.getId()));
            List<? extends GenericRepositoryObjectWrapper<?>> parentList = OmeroRawTools.getParentContainer(imageServer.getClient(), obj, qpNotif);
            if(!parentList.isEmpty())
                containers.putAll(getParentHierarchy(imageServer,parentList.get(0), qpNotif));
        } else if (obj instanceof WellWrapper) {
            containers.put("well-name", obj.getName());
            containers.put("well-id", String.valueOf(obj.getId()));
            List<? extends GenericRepositoryObjectWrapper<?>> parentList = OmeroRawTools.getParentContainer(imageServer.getClient(), obj, qpNotif);
            if(!parentList.isEmpty())
                containers.putAll(getParentHierarchy(imageServer,parentList.get(0), qpNotif));
        } else if (obj instanceof ImageWrapper) {
            List<? extends GenericRepositoryObjectWrapper<?>> parentList = OmeroRawTools.getParentContainer(imageServer.getClient(), obj, qpNotif);
            if(!parentList.isEmpty())
                containers.putAll(getParentHierarchy(imageServer, parentList.get(0), qpNotif));
        }

        return containers;
    }

    /**
     * read the rendering settings object linked to the specified pixels
     * Code partially copied {@link ImageWrapper#getChannelColor(Client, int)}
     *
     * @param client the client that handles the OMERO connection
     * @param pixelsId the id of the image pixels 
     * @return Image's rendering settings object
     *
     * @throws DSOutOfServiceException   Cannot connect to OMERO
     * @throws ServerError   An error occurred server side
     */
    public static RenderingDef readOmeroRenderingSettings(OmeroRawClient client, long pixelsId)
            throws DSOutOfServiceException, ServerError {

        Gateway gateway = client.getSimpleClient().getGateway();
        SecurityContext ctx = client.getSimpleClient().getCtx();

        // get rendering settings
        RenderingDef renderingDef = gateway.getRenderingSettingsService(ctx).getRenderingSettings(pixelsId);

        if(renderingDef == null) {
            // load rendering settings if they were not automatically loaded
            RenderingEnginePrx re = gateway.getRenderingService(ctx, pixelsId);
            re.lookupPixels(pixelsId);
            if (!(re.lookupRenderingDef(pixelsId))) {
                re.resetDefaultSettings(true);
                re.lookupRenderingDef(pixelsId);
            }
            re.load();
            re.close();
            return gateway.getRenderingSettingsService(ctx).getRenderingSettings(pixelsId);
        }
        return renderingDef;
    }

    /**
     * fetch all OMERO plates corresponding to the list of ids.
     * <br>
     * <p>This method is necessary to retrieve the entire Plate object for the OMERO browser and cannot
     * be replaced by client.getPlates().</p>
     *
     * @param client the client that handles the OMERO connection
     * @param plateIds the list of ids to fetch
     * @return List of OMERO plates objects
     *
     * @throws ServiceException   Cannot connect to OMERO.
     * @throws ServerError   An error occurred server side
     */
    public static List<PlateWrapper> readPlates(OmeroRawClient client, List<Long> plateIds)
            throws ServiceException, ServerError {

        String GET_PLATE_QUERY = "select p from Plate as p " +
                "left join fetch p.wells as w " +
                "left join fetch p.plateAcquisitions as pa " +
                "where p.id in (:ids)";

        IQueryPrx qs = client.getSimpleClient().getQueryService();
        ParametersI param = new ParametersI();

        param.addIds(plateIds);
        return qs.findAllByQuery(GET_PLATE_QUERY, param).stream()
                .map(PlateI.class::cast)
                .map(PlateData::new)
                .map(PlateWrapper::new)
                .collect(Collectors.toList());
    }


    /**
     * Update the thumbnail of an OMERO image.
     * <br> <br>
     * Be careful : the image should already have an OMERO ID.
     *
     * @param client the client that handles the OMERO connection
     * @param imageId the id of teh image
     * @param objectId renderingSettings ID
     * @return Updating status (True if updated ; false with error message otherwise)
     */
    public static boolean updateOmeroThumbnail(OmeroRawClient client, long imageId, long objectId){
        boolean wasAdded = true;

        // get the current image
        ImageWrapper image;

        // get OMERO thumbnail store
        ThumbnailStorePrx store;
        try {
            image = client.getSimpleClient().getImage(imageId);
            store = client.getSimpleClient().getGateway().getThumbnailService(client.getSimpleClient().getCtx());
        } catch(DSOutOfServiceException | AccessException | ExecutionException e){
            Utils.errorLog(logger,"OMERO Thumbnail", "Cannot read the Thumbnail service for image " + imageId,true);
           return false;
        }

        if(store == null){
            Utils.errorLog(logger,"OMERO Thumbnail", "Cannot get the Thumbnail service for image " + imageId,true);
            return false;
        }

        try {
            // get the pixel id to retrieve the correct thumbnail
            long pixelId = image.getPixels().getId();
            // get current thumbnail
            store.setPixelsId(pixelId);
            //set the new settings
            store.setRenderingDefId(objectId);

            try {
                // update the thumbnail
                store.createThumbnails();
            } catch (ServerError e) {
                // this is turn into a debug mode because it is always thrown even if the thumbnail is updated
                Utils.debugLog(logger,"OMERO Thumbnail", "Error during thumbnail creation but thumbnail is updated", e);
            }

        } catch (NullPointerException | ServerError e) {
            Utils.errorLog(logger,"OMERO Thumbnail", "Thumbnail cannot be updated for image " + imageId, e,true);
            wasAdded = false;
        }

        try {
            // close the store
            store.close();
        } catch (ServerError e) {
            Utils.errorLog(logger,"OMERO Thumbnail", "Cannot close the ThumbnailStore", e,true);
        }

        return wasAdded;
    }

    /**
     * read an image stored in the resource folder of the main class
     *
     * @param imageName name of the image to read
     * @return The read image or null if cannot be read
     */
    public static BufferedImage readLocalImage(String imageName){
        try {
            return ImageIO.read(OmeroRawTools.class.getClassLoader().getResource("images/"+imageName));
        }catch(Exception e){
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
        LogTools.warnOnce(logger, "readOmeroAnnotations(OmeroRawClient, DataObject) is deprecated - " +
                "use GenericRepositoryObjectWrapper.getAnnotations(Client) instead");
        try {
            // read annotations linked to the image
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), obj);

        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the object "+obj);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroWells(OmeroRawClient, long) is deprecated - " +
                "use PlateWrapper.getWells(Client) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getWells(client.getSimpleClient().getCtx(), plateId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading wells","You don't have the right to access wells in plate "+plateId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get user's orphaned images from the OMERO server
     *
     * @param client
     * @param userId
     * @return List of orphaned images
     * @deprecated use {@link Client#getOrphanedImages(ExperimenterWrapper)} instead
     */
    @Deprecated
    public static Collection<ImageData> readOrphanedImages(OmeroRawClient client, long userId) {
        LogTools.warnOnce(logger, "readOrphanedImages(OmeroRawClient, long) is deprecated - " +
                "use Client.getOrphanedImages(ExperimenterWrapper) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getOrphanedImages(client.getSimpleClient().getCtx(), userId);
        } catch (ExecutionException e) {
            Dialogs.showErrorMessage("Orphaned images","Cannot retrieved orphaned images for user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get all orphaned datasets from the OMERO server linked to the current group (contained in the security context of the current client).
     *
     * @param client
     * @return List of orphaned datasets
     * @deprecated user {@link Client#getOrphanedDatasets(ExperimenterWrapper)}} instead
     */
    @Deprecated
    public static Collection<DatasetData> readOmeroOrphanedDatasets(OmeroRawClient client)  {
        LogTools.warnOnce(logger, "readOmeroOrphanedDatasets(OmeroRawClient) is deprecated - " +
                "use Client.getOrphanedDatasets(ExperimenterWrapper) instead");

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
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated user {@link Client#getOrphanedDatasets(ExperimenterWrapper)} instead
     */
    @Deprecated
    public static Collection<DatasetData> readOrphanedDatasets(OmeroRawClient client, long userId) {
        LogTools.warnOnce(logger, "readOmeroOrphanedDatasets(OmeroRawClient, long) is deprecated - " +
                "use Client.getOrphanedDatasets(ExperimenterWrapper) instead");
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
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorMessage("Orphaned datasets","You don't have the right to access to orphaned dataset of the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroProjects(OmeroRawClient, List) is deprecated - " +
                "use Client.getProjects(Long...) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), projectIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects","An error occurs when reading OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects","You don't have the right to access OMERO projects "+projectIds);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroProjectsByUser(OmeroRawClient, long) is deprecated - " +
                "use Client.getProjects(ExperimenterWrapper) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getProjects(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroScreensByUser(OmeroRawClient, long) is deprecated - " +
                "use Client.getScreens(ExperimenterWrapper) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getScreens(client.getSimpleClient().getCtx(), userId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading projects by user","An error occurs when reading OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading projects by user","You don't have the right to access OMERO projects for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroDatasets(OmeroRawClient, List) is deprecated - " +
                "use Client.getDatasets(Long...) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getDatasets(client.getSimpleClient().getCtx(), datasetIds);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading datasets","An error occurs when reading OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch (DSAccessException | NoSuchElementException e){
            Dialogs.showErrorNotification("Reading datasets","You don't have the right to access OMERO datasets "+datasetIds);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroPlates(OmeroRawClient, List) is deprecated - " +
                "use OmeroRawTools.readPlates(OmeroRawClient, List) instead");
        List<PlateWrapper> plates;
        try{
            plates = readPlates(client, plateIds);
        }catch(ServerError | ServiceException e){
            Utils.errorLog(logger, "OMERO plates", "Cannot retrieve plates from OMERO",e,true);
            return Collections.emptyList();
        }
        return plates.stream().map(PlateWrapper::asDataObject).collect(Collectors.toList());
    }


    /**
     * Delete all existing ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param imageId
     * @deprecated use {@link Client#delete(Collection)} instead
     */
    @Deprecated
    public static void deleteAllOmeroROIs(OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "deleteAllOmeroROIs(OmeroRawClient, long) is deprecated - " +
                "use Client.delete(Collection) instead");
        try {
            // extract ROIData
            List<IObject> roiData = client.getSimpleClient().getImage(imageId).getROIs(client.getSimpleClient())
                    .stream()
                    .map(ROIWrapper::asDataObject)
                    .map(ROIData::asIObject)
                    .collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete of cannot delete them");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete ROIs on OMERO on the image  " + imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
    }

    /**
     * Delete the specified ROIs on OMERO that are linked to an image, specified by its id.
     *
     * @param client
     * @param roisToDelete
     * @deprecated use {@link Client#delete(Collection)}} instead
     */
    @Deprecated
    public static void deleteOmeroROIs(OmeroRawClient client, Collection<ROIData> roisToDelete) {
        LogTools.warnOnce(logger, "deleteOmeroROIs(OmeroRawClient, Collection) is deprecated - " +
                "use Client.delete(Collection) instead");
        try {
            // Convert to IObject
            List<IObject> roiData = roisToDelete.stream().map(ROIData::asIObject).collect(Collectors.toList());

            // delete ROis
            if(client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), roiData) == null)
                Dialogs.showInfoNotification("ROI deletion","No ROIs to delete");

        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("ROI deletion","Could not delete existing ROIs on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("ROI deletion", "You don't have the right to delete those ROIs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
    }

    /**
     * Send ROIs to OMERO server and attached them to the specified image.
     *
     * @param client
     * @param imageId
     * @param omeroRois
     * @return Sending status (True if sent ; false with error message otherwise)
     * @deprecated use {@link ImageWrapper#saveROIs(Client, ROIWrapper...)} instead
     */
    @Deprecated
    public static boolean writeOmeroROIs(OmeroRawClient client, long imageId, List<ROIData> omeroRois) {
        LogTools.warnOnce(logger, "deleteOmeroROIs(OmeroRawClient, long, List) is deprecated - " +
                "use ImageWrapper.saveROIs(Client, ROIWrapper...) instead");
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
                logger.error(Utils.getErrorStackTraceAsString(e));
            } catch (DSAccessException e){
                Dialogs.showErrorNotification("ROI Saving","You don't have the right to write ROIs from OMERO on the image "+imageId);
                logger.error(String.valueOf(e));
                logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "createOmeroROIsFromPathObjects(Collection) is deprecated - " +
                "will not be replaced");
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
        LogTools.warnOnce(logger, "createPathObjectsFromOmeroROIs(List) is deprecated - " +
                "will not be replaced");
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
        LogTools.warnOnce(logger, "getROIComment(Shape) is deprecated - will not be replaced");
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
        LogTools.warnOnce(logger, "getROIComment(ROIData) is deprecated - will not be replaced");
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
        LogTools.warnOnce(logger, "parseROIComment(String) is deprecated - will not be replaced");
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
        LogTools.warnOnce(logger, "updateKeyValuesOnOmero(List, OmeroRawClient) is deprecated - " +
                "will not be replaced");
        boolean wasUpdated = true;
        try {
            // update key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).updateObjects(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()),null);
        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("OMERO KeyValues update", "Cannot update existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasUpdated = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to update key value pairs on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link Client#delete(Collection)} instead
     */
    @Deprecated
    public static boolean deleteKeyValuesOnOmero(List<MapAnnotationData> keyValuePairs, OmeroRawClient client) {
        LogTools.warnOnce(logger, "deleteKeyValuesOnOmero(List, OmeroRawClient) is deprecated - " +
                "use Client.delete(Collection) instead");
        boolean wasDeleted = true;
        try {
            // remove current key-values
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), keyValuePairs.stream().map(MapAnnotationData::asIObject).collect(Collectors.toList()));
        } catch(ExecutionException | DSOutOfServiceException | DSAccessException e) {
            Dialogs.showErrorNotification("OMERO KeyValues deletion", "Cannot delete existing key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "checkUniqueKeyInAnnotationMap(List) is deprecated - " +
                "will not be replaced");
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
        LogTools.warnOnce(logger, "addKeyValuesOnOmero(Map, OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper) instead");
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageWrapper imageWrapper = client.getSimpleClient().getImage(imageId);

            // send key-values to OMERO
            imageWrapper.link(client.getSimpleClient(), new MapAnnotationWrapper(keyValuePairs));
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "Cannot add new key values on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO KeyValues", "You don't have the right to add some key value pairs on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readKeyValuesAsNamedValue(OmeroRawClient, long) is deprecated - " +
                "use ImageWrapper.getMapAnnotations(Client) and MapAnnotationWrapper.getContent() instead");
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
        LogTools.warnOnce(logger, "readKeyValues(OmeroRawClient, long) is deprecated - " +
                "use ImageWrapper.getMapAnnotations(Client) instead");

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
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readTags(OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.getTags(Client) instead");

        List<AnnotationData> annotations;
        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // read annotations linked to the image
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO Tags", "Cannot get tags from OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "You don't have the right to read tags from OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readUserTags(OmeroRawClient) is deprecated - use Client.getTags() instead");
        List<IObject> objects;

        try {
            // get current image from OMERO
            objects = client.getSimpleClient().getGateway().getQueryService(client.getSimpleClient().getCtx()).findAll(TagAnnotation.class.getSimpleName(),null);

        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO tags", "Error getting all available tags");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "addTagsOnOmero(TagAnnotationData, OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper) instead");
        boolean wasAdded = true;
        try {
            // get current image from OMERO
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // send key-values to OMERO
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), tags, imageData);

        }catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "Cannot add new tags on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Adding OMERO tags", "You don't have the right to add tags on OMERO on the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link ProjectWrapper#addDataset(Client, String, String)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName, String datasetDescription) {
        LogTools.warnOnce(logger, "createNewDataset(OmeroRawClient, long, String, String) is deprecated - " +
                "use ProjectWrapper.addDataset(Client, String, String) instead");
        try {
            ProjectWrapper projectWrapper = client.getSimpleClient().getProject(projectId);
            return projectWrapper.addDataset(client.getSimpleClient(), datasetName, datasetDescription).asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName+" in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO in the project "+projectId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link DatasetWrapper#DatasetWrapper(String, String)} instead followed by {@link DatasetWrapper#saveAndUpdate(Client)}
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName, String datasetDescription){
        LogTools.warnOnce(logger, "createNewDataset(OmeroRawClient, String, String) is deprecated - " +
                "use new DatasetWrapper(String, String) and DatasetWrapper.saveAndUpdate(Client) instead");
        try {
            DatasetWrapper datasetWrapper = new DatasetWrapper(datasetName, datasetDescription);
            datasetWrapper.saveAndUpdate(client.getSimpleClient());
            return datasetWrapper.asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }
    }

    /**
     * create a new orphaned dataset on OMERO
     *
     * @param client
     * @param datasetName
     * @return OMERO dataset
     * @deprecated use {@link DatasetWrapper#DatasetWrapper(String, String)} instead followed by {@link DatasetWrapper#saveAndUpdate(Client)}
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, String datasetName){
        LogTools.warnOnce(logger, "createNewDataset(OmeroRawClient, String) is deprecated - " +
                "use new DatasetWrapper(String, String) and DatasetWrapper.saveAndUpdate(Client) instead");
        try {
            DatasetWrapper datasetWrapper = new DatasetWrapper(datasetName,"");
            datasetWrapper.saveAndUpdate(client.getSimpleClient());
            return datasetWrapper.asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link ProjectWrapper#addDataset(Client, String, String)} instead
     */
    @Deprecated
    public static DatasetData createNewDataset(OmeroRawClient client, long projectId, String datasetName){
        LogTools.warnOnce(logger, "createNewDataset(OmeroRawClient, long, String) is deprecated - " +
                "use ProjectWrapper.addDataset(Client, String, String) instead");
        try {
            ProjectWrapper projectWrapper = client.getSimpleClient().getProject(projectId);
            return projectWrapper.addDataset(client.getSimpleClient(), datasetName, "").asDataObject();
        }catch(ExecutionException | DSOutOfServiceException  e) {
            Dialogs.showErrorNotification("Create New dataset", "Cannot create dataset "+datasetName);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }catch(DSAccessException e) {
            Dialogs.showErrorNotification("Create New dataset", "You don't have the right to create a dataset on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
        LogTools.warnOnce(logger, "readOmeroDataset(OmeroRawClient, Long) is deprecated - " +
                "use Client.getDataset(Long) instead");
        Collection<DatasetData> datasets = readOmeroDatasets(client, Collections.singletonList(datasetId));
        if(datasets.isEmpty())
            return null;
        return datasets.iterator().next();

    }

    /**
     * @return formatted date
     * @deprecated use {@link Utils#getCurrentDateAndHour()} instead
     */
    @Deprecated
    public static String getCurrentDateAndHour(){
        LogTools.warnOnce(logger, "getCurrentDateAndHour() is deprecated - " +
                "use Utils.getCurrentDateAndHour()) instead");
       return Utils.getCurrentDateAndHour();
    }

    /**
     *
     * @param e
     * @return stack trace
     * @deprecated use {@link Utils#getErrorStackTraceAsString(Exception)} instead
     */
    @Deprecated
    public static String getErrorStackTraceAsString(Exception e){
        LogTools.warnOnce(logger, "getErrorStackTraceAsString(Exception) is deprecated - " +
                "use Utils.getErrorStackTraceAsString(Exception)) instead");
        return Utils.getErrorStackTraceAsString(e);
    }

    /**
     * Splits the "target" map into two parts : one part containing key/values that are referenced in the "reference" map and
     * the other containing remaining key/values that are not referenced in the "reference".
     *
     * @param reference
     * @param target
     * @return List of new kvp and existing kvp maps
     * @deprecated use {@link Utils#splitNewAndExistingKeyValues(Map, Map)} instead
     */
    @Deprecated
    public static List<Map<String, String>> splitNewAndExistingKeyValues(Map<String, String> reference, Map<String, String> target){
        LogTools.warnOnce(logger, "splitNewAndExistingKeyValues(Map, Map) is deprecated - " +
                "use Utils.splitNewAndExistingKeyValues(Map, Map)) instead");
        Map<String, Map<String, String>> keyMap = Utils.splitNewAndExistingKeyValues(reference, target);

        // add the two separate maps to a list.
        List<Map<String, String>> results = new ArrayList<>();
        results.add(keyMap.get(Utils.EXISTING_KVP));
        results.add(keyMap.get(Utils.NEW_KVP));

        return results;
    }

    /**
     * Get OMERO image's channels
     *
     * @param client
     * @param imageId
     * @return List of channels objects of the specified image
     * @deprecated use {@link ImageWrapper#getChannels(Client)} instead
     */
    @Deprecated
    public static List<ChannelData> readOmeroChannels(OmeroRawClient client, long imageId){
        LogTools.warnOnce(logger, "readOmeroChannels(OmeroRawClient, long) is deprecated - " +
                "use ImageWrapper.getChannels(Client)) instead");
        try {
            // get channels
            return client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getChannelData(client.getSimpleClient().getCtx(), imageId);
        } catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Channel reading","Could not read image channel on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Channel reading","You don't have the right to read channels on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        }
    }

    /**
     * Get the image file format (ex. .lif, .vsi,...)
     *
     * @param client
     * @param imageId
     * @return Image file format
     * @deprecated use {@link ImageWrapper#getFormat()} instead
     */
    @Deprecated
    public static String readImageFileType(OmeroRawClient client, long imageId){
        LogTools.warnOnce(logger, "readImageFileType(OmeroRawClient, long) is deprecated - " +
                "use ImageWrapper.getFormat()) instead");
        try {
            ImageData imageData = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
            return imageData.asImage().getFormat().getValue().getValue();
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO annotations", "Cannot get annotations from OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return "";
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO annotations","You don't have the right to read annotations on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return "";
        }
    }

    /**
     * Download an image from OMERO in the path given in argument.
     *
     * @param client
     * @param imageId
     * @param path
     * @return Downloading status (True if downloaded ; false with error message otherwise)
     * @deprecated use {@link ImageWrapper#download(Client, String)} instead
     */
    @Deprecated
    public static boolean downloadImage(OmeroRawClient client, long imageId, String path){
        LogTools.warnOnce(logger, "downloadImage(OmeroRawClient, long, String) is deprecated - " +
                "use ImageWrapper.download(Client, String)) instead");
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
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasDownloaded = false;
        } catch(DSAccessException e){
            Dialogs.showErrorNotification("Download object","You don't have the right to download image "+imageId+" from OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link DatasetWrapper#importImage(Client, String)} instead
     */
    @Deprecated
    public static List<Long> uploadImage(OmeroRawClient client, long datasetId, String path){
        LogTools.warnOnce(logger, "uploadImage(OmeroRawClient, long, String) is deprecated - " +
                "use DatasetWrapper.importImage(Client, String)) instead");
        DatasetWrapper dataset;
        try {
            dataset = client.getSimpleClient().getDataset(datasetId);
            return uploadImage(client, dataset, path);
        }catch(DSAccessException | ServiceException | ExecutionException | OMEROServerError e) {
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
     * @deprecated use {@link DatasetWrapper#importImage(Client, String)} instead
     */
    @Deprecated
    public static List<Long> uploadImage(OmeroRawClient client, DatasetWrapper dataset, String path)
            throws AccessException, ServiceException, OMEROServerError, ExecutionException {
        LogTools.warnOnce(logger, "uploadImage(OmeroRawClient, DatasetWrapper, String) is deprecated - " +
                "use DatasetWrapper.importImage(Client, String)) instead");
        if(dataset == null){
            Dialogs.showErrorNotification("OMERO - Upload image", "The dataset you want to access does not exist");
            return Collections.emptyList();
        }
        return dataset.importImage(client.getSimpleClient(), path);
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
     * @deprecated use {@link ImageWrapper#getThumbnail(Client, int)} instead
     */
    @Deprecated
    public static BufferedImage getThumbnail(OmeroRawClient client, long imageId, int prefSize) {
        LogTools.warnOnce(logger, "getThumbnail(OmeroRawClient, long, int) is deprecated - " +
                "use ImageWrapper.getThumbnail(Client, int)) instead");
        // get the current defaultPixel
        ImageWrapper imageWrapper;
        try {
            imageWrapper = client.getSimpleClient().getImage(imageId);
            return imageWrapper.getThumbnail(client.getSimpleClient(), prefSize);
        } catch (ExecutionException | DSOutOfServiceException | DSAccessException | NullPointerException |
                 OMEROServerError | IOException e) {
            Dialogs.showErrorNotification("Thumbnail reading", "The thumbnail of image " + imageId + " cannot be read.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return readLocalImage(noImageThumbnail);
        }
    }

    /**
     * Update a list of OMERO objects
     *
     * @param client
     * @param objects
     * @return Updating status (True if updated ; false with error message otherwise)
     * @deprecated Method removed ; use the official API
     */
    @Deprecated
    public static boolean updateObjectsOnOmero(OmeroRawClient client, List<IObject> objects){
        LogTools.warnOnce(logger, "updateObjectsOnOmero(OmeroRawClient, List) is deprecated - " +
                "use the official API DataManagerFacility.updateObjects(SecurityContext, List, Parameters) instead");
        boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getDm().updateObjects(client.getSimpleClient().getCtx(), objects, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update objects","Error during updating objects on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update objects","You don't have the right to update objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * * @deprecated Method removed ; use the official API
     */
    @Deprecated
    public static boolean updateObjectOnOmero(OmeroRawClient client, IObject object){
        LogTools.warnOnce(logger, "updateObjectOnOmero(OmeroRawClient, IObject) is deprecated - " +
                "use the official API DataManagerFacility.updateObject(SecurityContext, IObject, Parameters) instead");
        boolean wasAdded = true;
        try{
            // update the object on OMERO
            client.getSimpleClient().getDm().updateObject(client.getSimpleClient().getCtx(), object, null);
        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Update object","Error during updating object on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Update object","You don't have the right to update object on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        }
        return wasAdded;
    }


    /**
     * Convert a QuPath measurement table to an OMERO table
     *
     * @param pathObjects
     * @param ob
     * @param client
     * @param imageId
     * @return The corresponding OMERO.Table
     * @deprecated Method moved with a non-public access
     */
    @Deprecated
    public static TableData convertMeasurementTableToOmeroTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob,
                                                                OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "convertMeasurementTableToOmeroTable(Collection, ObservableMeasurementTableData, OmeroRawClient, long) is deprecated - " +
                "will not be replaced");
        ImageWrapper imgWrapper;
        try {
            imgWrapper = client.getSimpleClient().getImage(imageId);
        }catch(Exception e){
            Utils.errorLog(logger, "Measurement table conversion", "Cannot read the image "+imageId+" from OMERO", true);
            return null;
        }
        return Utils.buildOmeroTableFromMeasurementTable(pathObjects, ob, imgWrapper);
    }

    /**
     * Send an OMERO.table to OMERO server and attach it to the image specified by its ID.
     *
     * @param table OMERO.table
     * @param name table name
     * @param client
     * @param imageId
     * @return Sending status (True if sent and attached ; false with error message otherwise)
     * @deprecated use {@link GenericRepositoryObjectWrapper#addTable(Client, TableWrapper)} instead
     */
    @Deprecated
    public static boolean addTableToOmero(TableData table, String name, OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "addTableToOmero(TableData, String, OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.addTable(Client, TableWrapper) instead");
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), image, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#addTable(Client, TableWrapper)} instead
     */
    @Deprecated
    public static TableData addTableToOmero(TableData table, String name, OmeroRawClient client, DataObject container) {
        LogTools.warnOnce(logger, "addTableToOmero(TableData, String, OmeroRawClient, DataObject) is deprecated - " +
                "use GenericRepositoryObjectWrapper.addTable(Client, TableWrapper) instead");
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).addTable(client.getSimpleClient().getCtx(), container, name, table);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Table Saving","Error during saving table on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Table Saving","You don't have the right to add a table on OMERO for "
                    + container.getClass().getName()+" id " +container.getId());
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, long, String) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static boolean addAttachmentToOmero(File file, OmeroRawClient client, long imageId, String miemtype, String description) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, long, String, String) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
        boolean wasAdded = true;
        try{
            // get the current image to attach the omero.table to
            ImageData image = client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);

            // attach the omero.table to the image
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), image).get();

        } catch (ExecutionException | DSOutOfServiceException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            wasAdded = false;
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("File Saving","You don't have the right to save a file on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, DataObject) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, DataObject, String) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    public static FileAnnotationData addAttachmentToOmero(File file, OmeroRawClient client, DataObject obj, String miemtype, String description) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(File, OmeroRawClient, DataObject, String, String) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachFile(client.getSimpleClient().getCtx(), file, miemtype, description, file.getName(), obj).get();

        } catch (ExecutionException | InterruptedException e){
            Dialogs.showErrorNotification("File Saving","Error during saving file on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#link(Client, GenericAnnotationWrapper[])} instead
     */
    @Deprecated
    static AnnotationData linkAnnotationToOmero(OmeroRawClient client, AnnotationData annotationData, DataObject obj) {
        LogTools.warnOnce(logger, "addAttachmentToOmero(OmeroRawClient, AnnotationData, DataObject) is deprecated - " +
                "use GenericRepositoryObjectWrapper.link(Client, GenericAnnotationWrapper[]) instead");
        try{
            // attach the omero.table to the image
            return client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).attachAnnotation(client.getSimpleClient().getCtx(), annotationData, obj);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Link Annotation","Error during linking the annotation on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("Link Annotation","You don't have the right to link objects on OMERO ");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }
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
     * @deprecated Method moved to a non-public class
     */
    @Deprecated
    public static File buildCSVFileFromMeasurementTable(Collection<PathObject> pathObjects, ObservableMeasurementTableData ob,
                                                        long imageId, String name, String path) {
        LogTools.warnOnce(logger, "buildCSVFileFromMeasurementTable(Collection, ObservableMeasurementTableData, long, String, String) is deprecated - " +
                "will not be replaced");
        return Utils.buildCSVFileFromMeasurementTable(pathObjects, ob, imageId, name);
    }


    /**
     * Get attachments from OMERO server attached to the specified image.
     *
     * @param client
     * @param imageId
     * @return Sending status (True if retrieved ; false with error message otherwise)
     * @deprecated use {@link GenericRepositoryObjectWrapper#getFileAnnotations(Client)} instead
     */
    @Deprecated
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "readAttachments(OmeroRawClient, long) is deprecated - " +
                "use GenericRepositoryObjectWrapper.getFileAnnotations(Client) instead");
        List<AnnotationData> annotations;
        try{
            // read image
            ImageData image = client.getSimpleClient().getImage(imageId).asDataObject();

            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), image, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link GenericRepositoryObjectWrapper#getFileAnnotations(Client)} instead
     */
    @Deprecated
    public static List<FileAnnotationData> readAttachments(OmeroRawClient client, DataObject parent) {
        LogTools.warnOnce(logger, "readAttachments(OmeroRawClient, DataObject) is deprecated - " +
                "use GenericRepositoryObjectWrapper.getFileAnnotations(Client) instead");
        List<AnnotationData> annotations;
        try{
            // get annotations
            List<Class<? extends AnnotationData>> types = Collections.singletonList(FileAnnotationData.class);
            annotations = client.getSimpleClient().getGateway().getFacility(MetadataFacility.class).getAnnotations(client.getSimpleClient().getCtx(), parent, types, null);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "Cannot read attachment from "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return Collections.emptyList();
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading",
                    "You don't have the right to read attachments on OMERO for "+parent.getClass().getName()+" id "+parent.getId());
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
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
     * @deprecated use {@link ImageWrapper#getTables(Client)} instead
     */
    @Deprecated
    public static Collection<FileAnnotationData> readTables(OmeroRawClient client, long imageId) {
        LogTools.warnOnce(logger, "readTables(OmeroRawClient, long) is deprecated - " +
                "use ImageWrapper.getTables(Client) instead");
        try{
            // read image
            ImageData image = client.getSimpleClient().getImage(imageId).asDataObject();

            // get annotations
            return client.getSimpleClient().getGateway().getFacility(TablesFacility.class).getAvailableTables(client.getSimpleClient().getCtx(), image);

        } catch (ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Attachment reading","Cannot read attachment from image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Attachment reading","You don't have the right to read attachments on OMERO for the image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
        return Collections.emptyList();
    }

    /**
     * Delete given files on OMERO
     *
     * @param client
     * @param data
     * @deprecated use {@link Client#delete(Collection)} instead
     */
    @Deprecated
    public static boolean deleteFiles(OmeroRawClient client, List<FileAnnotationData> data){
        LogTools.warnOnce(logger, "deleteFiles(OmeroRawClient, List) is deprecated - " +
                "use Client.delete(Collection) instead");
        boolean hasBeenDeleted = false;

        try{
            List<IObject> IObjectData = data.stream().map(FileAnnotationData::asIObject).collect(Collectors.toList());
            client.getSimpleClient().getGateway().getFacility(DataManagerFacility.class).delete(client.getSimpleClient().getCtx(), IObjectData);
            hasBeenDeleted = true;
        } catch (DSOutOfServiceException |  ExecutionException e){
            Dialogs.showErrorNotification("File deletion","Could not delete files on OMERO.");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e) {
            Dialogs.showErrorNotification("File deletion", "You don't have the right to delete those files on OMERO");
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
        return hasBeenDeleted;
    }


    /**
     * Get an image from OMERO server, corresponding to the specified id.
     *
     * @param client
     * @param imageId
     * @return OMERO image object
     * @deprecated use {@link Client#getImage(Long)} instead
     */
    @Deprecated
    public static ImageData readOmeroImage(OmeroRawClient client, long imageId){
        LogTools.warnOnce(logger, "readOmeroImage(OmeroRawClient, long) is deprecated - " +
                "use Client.getImage(Long) instead");
        try {
            return client.getSimpleClient().getGateway().getFacility(BrowseFacility.class).getImage(client.getSimpleClient().getCtx(), imageId);
        }catch(ExecutionException | DSOutOfServiceException e){
            Dialogs.showErrorNotification("Reading image","An error occurs when reading OMERO image "+imageId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }catch (DSAccessException | NoSuchElementException e){
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            return null;
        }
    }


    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClassData The object class must implement DataObject class
     * @param id
     * @return
     * @deprecated method removed
     */
    @Deprecated
    private static DataObject readObject(OmeroRawClient client, String objectClassData, long id){
        LogTools.warnOnce(logger, "readObject(OmeroRawClient, String, long) is deprecated - " +
                "will not be replaced");
        try {
            return  client.getSimpleClient().getBrowseFacility().findObject(client.getSimpleClient().getCtx(), objectClassData, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClassData+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClassData+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
        return null;
    }


    /**
     * Get any Pojo object from OMERO
     * @param client
     * @param objectClass The object class must implement IObject class
     * @param id
     * @return
     * @deprecated method removed
     */
    @Deprecated
    private static IObject readIObject(OmeroRawClient client, String objectClass, long id){
        LogTools.warnOnce(logger, "readIObject(OmeroRawClient, String, long) is deprecated - " +
                "will not be replaced");
        try {
            return  client.getSimpleClient().getBrowseFacility().findIObject(client.getSimpleClient().getCtx(), objectClass, id, true);
        } catch(ExecutionException | DSOutOfServiceException e) {
            Dialogs.showErrorNotification("Reading OMERO object", "Cannot get "+objectClass+" from OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        } catch (DSAccessException e){
            Dialogs.showErrorNotification("Reading OMERO object","You don't have the right to read "+objectClass+" on OMERO with id "+id);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
        }
        return null;
    }

    /**
     * Retrieve a user on OMERO based on its ID
     *
     * @param client
     * @param userId
     * @param username
     * @return The specified OMERO user
     * @deprecated use {@link Client#getUser(long)} or {@link Client#getUser(String)} instead
     */
    @Deprecated
    public static ExperimenterWrapper getOmeroUser(OmeroRawClient client, long userId, String username){
        LogTools.warnOnce(logger, "getOmeroUser(OmeroRawClient, long, String) is deprecated - " +
                "use Client.getUser(long) or Client.getUser(String) instead");
        try {
            return client.getSimpleClient().getUser(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO user "+username +" ; id: "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * Retrieve all users within the group on OMERO
     *
     * @param client
     * @param groupId
     * @return A list of all OMERO user within the specified group
     * @deprecated use {@link GroupWrapper#getExperimenters()} instead and {@link Client#getGroup(long)} to get the
     * {@link GroupWrapper} object
     */
    @Deprecated
    public static List<Experimenter> getOmeroUsersInGroup(OmeroRawClient client, long groupId){
        LogTools.warnOnce(logger, "getOmeroUsersInGroup(OmeroRawClient, long) is deprecated - " +
                "use Client.getGroup(long).getExperimenters() instead");
        try {
            return client.getSimpleClient().getGroup(groupId).getExperimenters().stream()
                    .map(ExperimenterWrapper::asDataObject)
                    .map(ExperimenterData::asExperimenter).
                    collect(Collectors.toList());
        } catch (ServiceException | OMEROServerError e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read OMERO users in group "+groupId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            throw new RuntimeException("Cannot read OMERO users in group "+groupId, e);
        }
    }


    /**
     * Retrieve a group on OMERO based on its id
     *
     * @param client
     * @param groupId
     * @return The specified OMERO group
     * @deprecated use {@link Client#getGroup(long)} or {@link Client#getGroup(String)}instead
     */
    @Deprecated
    public static ExperimenterGroup getOmeroGroup(OmeroRawClient client, long groupId, String username){
        LogTools.warnOnce(logger, "getOmeroUsersInGroup(OmeroRawClient, long, String) is deprecated - " +
                "use Client.getGroup(long) or Client.getGroup(String) instead");
        try {
            return client.getSimpleClient().getGroup(groupId).asDataObject().asGroup();
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO - admin","Cannot read OMERO group with id: "+groupId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * get all the groups where the current user is member of
     *
     * @param client
     * @param userId
     * @return The list of user's OMERO groups
     * @deprecated use {@link ExperimenterWrapper#getGroups()} instead and {@link Client#getUser(long)} to get
     * {@link ExperimenterWrapper} object.
     */
    @Deprecated
    public static List<ExperimenterGroup> getUserOmeroGroups(OmeroRawClient client, long userId) {
        LogTools.warnOnce(logger, "getUserOmeroGroups(OmeroRawClient, long) is deprecated - " +
                "use Client.getUser(long).getGroups() instead");
        return client.getLoggedInUser().getGroups().stream()
                .map(GroupWrapper::asDataObject)
                .map(GroupData::asGroup)
                .collect(Collectors.toList());
    }

    /**
     * Get the default OMERO group of the specified user
     *
     * @param client
     * @param userId
     * @return User's OMERO default group
     * @deprecated use {@link ExperimenterWrapper#getDefaultGroup()} instead and {@link Client#getUser(long)} to get
     * the {@link ExperimenterWrapper} object.
     */
    @Deprecated
    public static ExperimenterGroup getDefaultOmeroGroup(OmeroRawClient client, long userId) {
        LogTools.warnOnce(logger, "getDefaultOmeroGroup(OmeroRawClient, long) is deprecated - " +
                "use Client.getUser(long).getDefaultGroup() instead");
        try {
            return client.getSimpleClient().getGateway().getAdminService(client.getSimpleClient().getCtx()).getDefaultGroup(userId);
        } catch (ServerError | DSOutOfServiceException e) {
            Dialogs.showErrorMessage("OMERO admin","Cannot read the default OMERO group for the user "+userId);
            logger.error(String.valueOf(e));
            logger.error(Utils.getErrorStackTraceAsString(e));
            throw new RuntimeException(e);
        }
    }

    /**
     * get all the groups on OMERO server. This functionality is reserved to Admin people. In case you are not
     * Admin, {@link ExperimenterWrapper#getGroups()} method is called instead.
     *
     * @param client
     * @return The list of all groups on OMERO server
     * @deprecated use {@link Client#getGroups()} if the user is admin. Otherwise, use {@link ExperimenterWrapper#getGroups()}
     */
    @Deprecated
    public static List<ExperimenterGroup> getAllOmeroGroups(OmeroRawClient client) {
        LogTools.warnOnce(logger, "getAllOmeroGroups(OmeroRawClient) is deprecated - " +
                "use Client.getGroups() if the user is admin ; otherwise use Client.getLoggedInUser().getGroups()");
        List<GroupWrapper >groups;
        if(client.isAdmin()) {
            try {
                groups = client.getSimpleClient().getGroups();
            } catch (AccessException | ServiceException e){
                Utils.errorLog(logger, "OMERO - Admin", "Cannot retrieve all the groups from the database. Only get your groups",e,true);
                groups = client.getLoggedInUser().getGroups();
            }
        }
        else
            groups = client.getLoggedInUser().getGroups();

        return groups.stream()
                .map(GroupWrapper::asDataObject)
                .map(GroupData::asGroup)
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
     * @deprecated use {@link OmeroRawTools#getParentContainer(OmeroRawClient, GenericRepositoryObjectWrapper, boolean)} instead
     */
    @Deprecated
    public static Collection<? extends DataObject> getParent(OmeroRawClient client, String dataType, long id){
        LogTools.warnOnce(logger, "getParent(OmeroRawClient, String, long) is deprecated - " +
                "use getParentContainer(OmeroRawClient, GenericRepositoryObjectWrapper, boolean) instead");
        try {
            GenericRepositoryObjectWrapper<?> container;
            switch(dataType.toLowerCase()){
                case "image":
                    container = client.getSimpleClient().getImage(id);
                    break;
                case "dataset":
                    container = client.getSimpleClient().getDataset(id);
                    break;
                case "project":
                    container = client.getSimpleClient().getProject(id);
                    break;
                case "well":
                    container = client.getSimpleClient().getWell(id);
                    break;
                case "plate":
                    container = client.getSimpleClient().getPlate(id);
                    break;
                case "screen":
                    container = client.getSimpleClient().getScreen(id);
                    break;
                default: container = null;
            }
            if(container != null) {
                List<? extends GenericRepositoryObjectWrapper<?>> parentList = getParentContainer(client, container, true);
                return parentList.stream().map(GenericRepositoryObjectWrapper::asDataObject).collect(Collectors.toList());
            }else{
                Utils.errorLog(logger, "OMERO parent container", "The container '"+dataType+"' ; id "+id+", is not supported ", true);
                return Collections.emptyList();
            }
        }catch(ServiceException | OMEROServerError | AccessException | ExecutionException e){
            Utils.errorLog(logger, "OMERO parent container", "Cannot get the parent of container "+dataType+" ; id "+id, true);
            return Collections.emptyList();
        }
    }
}