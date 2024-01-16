package qupath.ext.biop.servers.omero.raw.utils;

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


import java.awt.Color;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.roi.EllipseWrapper;
import fr.igred.omero.roi.GenericShapeWrapper;
import fr.igred.omero.roi.LineWrapper;
import fr.igred.omero.roi.PointWrapper;
import fr.igred.omero.roi.PolygonWrapper;
import fr.igred.omero.roi.PolylineWrapper;
import fr.igred.omero.roi.ROIWrapper;
import fr.igred.omero.roi.RectangleWrapper;
import javafx.collections.ObservableList;
import omero.gateway.model.EllipseData;
import omero.gateway.model.LineData;
import omero.gateway.model.PointData;
import omero.gateway.model.PolygonData;
import omero.gateway.model.PolylineData;
import omero.gateway.model.RectangleData;
import omero.gateway.model.ShapeData;

import omero.model.Ellipse;
import omero.model.Label;
import omero.model.Line;
import omero.model.Mask;
import omero.model.Point;
import omero.model.Polygon;
import omero.model.Polyline;
import omero.model.Rectangle;
import omero.model.Roi;
import omero.model.Shape;

import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.color.ColorToolsAwt;
import qupath.lib.geom.Point2;
import qupath.lib.gui.scripting.QPEx;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;

import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.GeometryROI;
import qupath.lib.roi.LineROI;
import qupath.lib.roi.PointsROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.PolylineROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;


public class OmeroRawShapes {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawShapes.class);


    /**
     * Create a PathObject of type annotation or detection from a QuPath ROI object
     * without any class and with the default red color.
     * See {@link #createPathObjectFromQuPathRoi(ROI roi, String roiType, String roiClass)} for the full documentation.
     *
     * @param roi roi to convert
     * @param roiType annotation or detection type
     * @return a new pathObject
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType){
        return createPathObjectFromQuPathRoi(roi, roiType,"");
    }

    /**
     * Create a PathObject of type annotation or detection from a QuPath ROI object, with a certain color. If the
     * color is yellow (i.e. default color on OMERO), it is automatically converted to QuPath default red color.
     * No class is assigned to the new PathObject.
     * See {@link #createPathObjectFromQuPathRoi(ROI roi, String roiType, String roiClass)} for the full documentation.
     *
     * @param roi roi to convert
     * @param roiType annotation or detection type
     * @param color color to assign to the pathObject
     * @return a new pathObject
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType, Color color){
        PathObject pathObject = createPathObjectFromQuPathRoi(roi, roiType, "");

        // check if the color is not the default color (yellow) for selected objects
        if(color == null || color.equals(Color.YELLOW))
            pathObject.setColor(Color.RED.getRGB());
        else pathObject.setColor(color.getRGB());

        return pathObject;
    }


    /**
     * Create a PathObject of type annotation or detection from a QuPath ROI object.
     * with the specified class. If the class is not a valid class, no class is assigned to the pathObject and
     * the default red color is assigned to it.
     * <br>
     * Currently, pathObjects of "cell" type are not supported and are considered as detections.
     * All pathObjects of other type (i.e. non recognized types) are automatically assigned to annotation type.
     *
     * @param roi roi to convert
     * @param roiType annotation or detection type
     * @param roiClass pathClasses assigned to the roi
     * @return a new pathObject
     */
    public static PathObject createPathObjectFromQuPathRoi(ROI roi, String roiType, String roiClass){
        PathObject pathObject;
        boolean isValidClass = !(roiClass == null || roiClass.isEmpty() || roiClass.equalsIgnoreCase("noclass"));

        // get all potential classes of a ROI
        List<String> classes = new ArrayList<>();
        if(isValidClass)
            classes.addAll(Arrays.stream(roiClass.split("&")).collect(Collectors.toList()));

        // create new PathClasses if they are not already created
        ObservableList<PathClass> availablePathClasses = QPEx.getQuPath().getAvailablePathClasses();
        boolean updateAvailablePathClasses = false;

        for (String pathClassName : classes) {
            PathClass newPathClass = PathClass.fromString(pathClassName);
            if (!availablePathClasses.contains(newPathClass)) {
                updateAvailablePathClasses = true;
                availablePathClasses.add(newPathClass);
            }
        }

        if(updateAvailablePathClasses)
            QPEx.getQuPath().getProject().setPathClasses(availablePathClasses);

        switch(roiType.toLowerCase()){
            case "cell": roiType = "detection";
            case "detection":
                if (!isValidClass)
                    pathObject = PathObjects.createDetectionObject(roi);
                else
                    pathObject = PathObjects.createDetectionObject(roi, PathClass.fromCollection(classes));
                break;
            case "annotation":
            default:
                if (!isValidClass)
                    pathObject = PathObjects.createAnnotationObject(roi);
                else
                    pathObject = PathObjects.createAnnotationObject(roi, PathClass.fromCollection(classes));
                break;
        }

        if(!isValidClass)
            pathObject.setColor(Color.RED.getRGB());

        return pathObject;
    }


    /**
     * Convert OMERO ROIs To QuPath ROIs. <br>
     * For annotations, it takes into account complex ROIs (with multiple shapes) by applying a XOR operation to reduce the dimensionality. <br>
     * For detections, no complex ROIs are possible (i.e. one shape = one ROI)
     *
     * @param roiWrapper OMERO ROI to convert
     * @return QuPath ROI
     */
    public static ROI convertOmeroROIsToQuPathROIs(ROIWrapper roiWrapper) {
        // Convert OMERO ROIs into QuPath ROIs
        List<ROI> roi = convertOmeroShapesToQuPathROIs(roiWrapper);

        if (!roi.isEmpty()) {
            // get the number of ROI "Point" in all shapes attached the current ROI
            // Points are not supported during the XOR operation ; they are processed differently.
            long nbPoints = roi.stream().filter(e -> e.getRoiName().equals("Points")).count();
            ROI finalROI = roi.get(0);

            // process ROIs with multiple points only
            if (nbPoints == roi.size() && roi.size() > 1) {
                // create a pointsROI instance with multiple points
                finalROI = ROIs.createPointsROI(roi.stream().mapToDouble(ROI::getCentroidX).toArray(),
                        roi.stream().mapToDouble(ROI::getCentroidY).toArray(),
                        ImagePlane.getPlaneWithChannel(roi.get(0).getC(), Math.max(roi.get(0).getZ(), 0), Math.max(roi.get(0).getT(), 0)));
            }

            // Process ROIs with multiple shapes, containing one or more points
            else if (nbPoints > 0 && roi.size() > 1) {
                List<ROI> pointsList = roi.stream().filter(e -> e.getRoiName().equals("Points")).collect(Collectors.toList());
                List<ROI> notPointsList = roi.stream().filter(e -> !e.getRoiName().equals("Points")).collect(Collectors.toList());

                // create a pointsROI instance with multiple points
                ROI pointsROI = ROIs.createPointsROI(pointsList.stream().mapToDouble(ROI::getCentroidX).toArray(),
                        pointsList.stream().mapToDouble(ROI::getCentroidY).toArray(),
                        ImagePlane.getPlaneWithChannel(pointsList.get(0).getC(), Math.max(pointsList.get(0).getZ(), 0), Math.max(pointsList.get(0).getT(), 0)));

                // make a complex roi by applying XOR operation between shapes
                finalROI = notPointsList.get(0);
                for (int k = 1; k < notPointsList.size(); k++) {
                    finalROI = linkShapes(finalROI, notPointsList.get(k));
                }

                // make the union between points and complex ROI
                finalROI = RoiTools.combineROIs(finalROI, pointsROI, RoiTools.CombineOp.ADD);
            }

            // Process ROIs with single shape AND ROIs with multiple shapes that do not contain points
            else {
                for (int k = 1; k < roi.size(); k++) {
                    // make a complex roi by applying XOR operation between shapes
                    finalROI = linkShapes(finalROI, roi.get(k));
                }
            }
            return finalROI;
        }
        return null;
    }

    /**
     * Convert OMERO shapes To QuPath ROIs.
     * <br>
     * ***********************BE CAREFUL***************************** <br>
     * For the z and t in the ImagePlane, if z < 0 and t < 0 (meaning that roi should be present on all the slices/frames),
     * only the first slice/frame is taken into account (meaning that roi are only visible on the first slice/frame) <br>
     * ****************************************************************
     *
     * @param roiWrapper OMERO Roi to convert
     * @return List of QuPath ROIs (one OMERO shape = one QuPath ROI)
     */
    private static List<ROI> convertOmeroShapesToQuPathROIs(ROIWrapper roiWrapper){
        // get the ROI
        Roi omeROI = (Roi) roiWrapper.asDataObject().asIObject();

        // get the shapes contained in the ROI (i.e. holes or something else)
        List<Shape> shapes = omeROI.copyShapes();
        List<ROI> list = new ArrayList<>();

        // Iterate on shapes, select the correct instance and create the corresponding QuPath ROI
        for (Shape shape:shapes) {

            if(shape instanceof Rectangle){
                RectangleData s = new RectangleData(shape);
                list.add(ROIs.createRectangleROI(s.getX(),s.getY(),s.getWidth(),s.getHeight(),ImagePlane.getPlaneWithChannel(s.getC(), Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Ellipse){
                EllipseData s = new EllipseData(shape);
                list.add(ROIs.createEllipseROI(s.getX()-s.getRadiusX(),s.getY()-s.getRadiusY(),s.getRadiusX()*2, s.getRadiusY()*2,ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof omero.model.Point){
                PointData s = new PointData(shape);
                list.add(ROIs.createPointsROI(s.getX(),s.getY(),ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Polyline){
                PolylineData s = new PolylineData(shape);
                list.add(ROIs.createPolylineROI(s.getPoints().stream().mapToDouble(Point2D.Double::getX).toArray(),
                        s.getPoints().stream().mapToDouble(Point2D.Double::getY).toArray(),
                        ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof omero.model.Polygon){
                PolygonData s = new PolygonData(shape);
                list.add(ROIs.createPolygonROI(s.getPoints().stream().mapToDouble(Point2D.Double::getX).toArray(),
                        s.getPoints().stream().mapToDouble(Point2D.Double::getY).toArray(),
                        ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Label){
                logger.warn("No ROIs created (requested label shape is unsupported)");
                //s=new TextData(shape);

            }else if(shape instanceof Line){
                LineData s = new LineData(shape);
                list.add(ROIs.createLineROI(s.getX1(),s.getY1(),s.getX2(),s.getY2(),ImagePlane.getPlaneWithChannel(s.getC(),Math.max(s.getZ(), 0), Math.max(s.getT(), 0))));

            }else if(shape instanceof Mask){
                logger.warn("No ROIs created (requested Mask shape is not supported yet)");
                //s=new MaskData(shape);
            }else{
                logger.warn("Unsupported shape ");
            }
        }

        return list;
    }


    /**
     * Output the ROI result of the XOR operation between the roi1 and roi2
     * <br>
     * ***********************BE CAREFUL***************************** <br>
     * For the c, z and t in the ImagePlane, if the rois contains in the general ROI are not contained in the same plane,
     * the new composite ROI are set on the lowest c/z/t plane <br>
     * ****************************************************************
     *
     * @param roi1
     * @param roi2
     * @return Results of the XOR operation between the two ROIs
     */
    private static ROI linkShapes(ROI roi1, ROI roi2){
        // get the area of the first roi
        Area a1 = new Area(roi1.getShape());

        // get the area of the second roi
        Area a2 = new Area(roi2.getShape());

        // Apply a xor operation on both area to combine them (ex. make a hole)
        a1.exclusiveOr(a2);

        // get the area of the newly created area
        java.awt.Rectangle r = a1.getBounds();

        // Assign the new ROI to the lowest valid plan of the stack
        return ROIs.createAreaROI(a1, ImagePlane.getPlaneWithChannel(Math.min(roi1.getC(), roi2.getC()),
                Math.min(roi1.getZ(), roi2.getZ()),
                Math.min(roi1.getT(), roi2.getT())));
    }




    /**
     * Convert a PathObject into OMERO-readable objects. In case the PathObject contains holes (i.e. complex annotation), it is split
     * into individual shapes and each shape will be part of the same OMERO ROI (nested hierarchy in OMERO.web).
     *
     * @param src : pathObject to convert
     * @param objectID pathObject's ID
     * @param parentID pathObject parent's ID
     * @return list of OMERO shapes
     */
    public static List<GenericShapeWrapper<? extends ShapeData>> convertQuPathRoiToOmeroRoi(PathObject src, String objectID, String parentID) {
        ROI roi = src.getROI();

        List<GenericShapeWrapper<? extends ShapeData>> shapes = new ArrayList<>();
        if (roi instanceof RectangleROI) {
            // Build the OMERO object
            RectangleWrapper rectangle = new RectangleWrapper(roi.getBoundsX(), roi.getBoundsY(), roi.getBoundsWidth(), roi.getBoundsHeight());
            // Write in comments the type of PathObject as well as the assigned class if there is one
            rectangle.setText(setRoiComment(src, objectID, parentID));

            // set the ROI position in the image
            rectangle.setC(roi.getC());
            rectangle.setT(roi.getT());
            rectangle.setZ(roi.getZ());
            shapes.add(rectangle);

        } else if (roi instanceof EllipseROI) {
            EllipseWrapper ellipse = new EllipseWrapper(roi.getCentroidX(), roi.getCentroidY(), roi.getBoundsWidth()/2, roi.getBoundsHeight()/2);
            ellipse.setText(setRoiComment(src, objectID, parentID));
            ellipse.setC(roi.getC());
            ellipse.setT(roi.getT());
            ellipse.setZ(roi.getZ());
            shapes.add(ellipse);

        } else if (roi instanceof LineROI) {
            LineROI lineRoi = (LineROI)roi;
            LineWrapper line = new LineWrapper(lineRoi.getX1(), lineRoi.getY1(), lineRoi.getX2(), lineRoi.getY2());
            line.setText(setRoiComment(src, objectID, parentID));
            line.setC(roi.getC());
            line.setT(roi.getT());
            line.setZ(roi.getZ());
            shapes.add(line);

        } else if (roi instanceof PolylineROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolylineWrapper polyline = new PolylineWrapper(points);
            polyline.setText(setRoiComment(src, objectID, parentID));
            polyline.setC(roi.getC());
            polyline.setT(roi.getT());
            polyline.setZ(roi.getZ());
            shapes.add(polyline);

        } else if (roi instanceof PolygonROI) {
            List<Point2D.Double> points = new ArrayList<>();
            roi.getAllPoints().forEach(point2->points.add(new Point2D.Double(point2.getX(), point2.getY())));
            PolygonWrapper polygon = new PolygonWrapper(points);
            polygon.setText(setRoiComment(src, objectID, parentID));
            polygon.setC(roi.getC());
            polygon.setT(roi.getT());
            polygon.setZ(roi.getZ());
            shapes.add(polygon);

        } else if (roi instanceof PointsROI) {
            List<Point2> roiPoints = roi.getAllPoints();

            for (Point2 roiPoint : roiPoints) {
                PointWrapper point = new PointWrapper(roiPoint.getX(), roiPoint.getY());
                point.setText(setRoiComment(src, objectID, parentID));
                point.setC(roi.getC());
                point.setT(roi.getT());
                point.setZ(roi.getZ());
                shapes.add(point);
            }

        } else if (roi instanceof GeometryROI) {
            // MultiPolygon
            logger.info("MultiPolygon will be split for OMERO compatibility.");

            // split the ROI into each individual shape
            List<ROI> rois = RoiTools.splitROI(roi);
            rois = splitHolesAndShape(rois);

            // process each individual shape
            for (ROI value : rois) {
                if(!(value ==null))
                    shapes.addAll(convertQuPathRoiToOmeroRoi(PathObjects.createAnnotationObject(value, src.getPathClass()), objectID, parentID));
            }

        } else {
            logger.warn("Unsupported type {}", roi.getRoiName());
            return null;
        }
        return shapes;
    }


    /**
     * Write the formatted ROI comment  => "type:class1&class2:objectID:parentID"
     *
     * @param src : pathObject to get pathClasses from
     * @param objectID pathObject's ID
     * @param parentID pathObject parent's ID
     *
     * @return formatted comment
     */
    private static String setRoiComment(PathObject src, String objectID, String parentID){

        // format classes to OMERO-compatible string
        String pathClass = src.getPathClass() == null ? "NoClass" : src.getPathClass().toString().replaceAll(":","&");

        if (src.isDetection()) {
             return "Detection:"+pathClass+":"+objectID+":"+parentID;
        } else {
            return "Annotation:"+pathClass+":"+objectID+":"+parentID;
        }
    }


    /**
     * Split holes and envelop of the same polygon into independent ROIs.
     *
     * @param rois List of ROIs
     * @return list of external and internal rings from ROIs
     */
    private static List<ROI> splitHolesAndShape(List<ROI> rois) {
        List<ROI> polygonROIs = new ArrayList<>();
        for (ROI roi : rois) {
            // Only process shapes that are polygons
            if (roi.getGeometry() instanceof org.locationtech.jts.geom.Polygon) {
                // get the external shape of the polygon
                org.locationtech.jts.geom.Polygon polygon = (org.locationtech.jts.geom.Polygon) roi.getGeometry();

                Coordinate[] polygonCoordinates = polygon.getExteriorRing().getCoordinates();
                List<Point2> polygonROICoordinates = new ArrayList<>();

                for (Coordinate polygonCoordinate : polygonCoordinates) {
                    polygonROICoordinates.add(new Point2(polygonCoordinate.x, polygonCoordinate.y));
                }

                polygonROIs.add(createROI(polygonROICoordinates, roi));

                // get the internal shapes of the polygon (i.e. holes)
                for (int j = 0; j < polygon.getNumInteriorRing(); j++) {
                    polygonCoordinates = polygon.getInteriorRingN(j).getCoordinates();
                    polygonROICoordinates = new ArrayList<>();

                    for (Coordinate polygonCoordinate : polygonCoordinates) {
                        polygonROICoordinates.add(new Point2(polygonCoordinate.x, polygonCoordinate.y));
                    }

                    polygonROIs.add(createROI(polygonROICoordinates, roi));
                }
            } else {
                polygonROIs.add(roi);
            }
        }
        return polygonROIs;
    }

    /**
     * Convert QuPath pathObjects into OMERO ROIs.
     *
     * @param pathObjects
     * @return List of OMERO ROIs
     */
    protected static List<ROIWrapper> createOmeroROIsFromPathObjects(Collection<PathObject> pathObjects){
        List<ROIWrapper> omeroRois = new ArrayList<>();
        Map<PathObject,String> idObjectMap = new LinkedHashMap<>();
        Map<String,List<ROIWrapper>> pathClassROIsMap = new TreeMap<>(Comparator.naturalOrder());

        // create unique ID for each object
        pathObjects.forEach(pathObject -> idObjectMap.put(pathObject, pathObject.getID().toString()));

        pathObjects.forEach(pathObject -> {
            // computes OMERO-readable ROIs
            List<GenericShapeWrapper<? extends ShapeData>> shapes = OmeroRawShapes.convertQuPathRoiToOmeroRoi(pathObject,
                    idObjectMap.get(pathObject), pathObject.getParent() == null ? "NoParent" : idObjectMap.get(pathObject.getParent()));
            if (!(shapes == null) && !(shapes.isEmpty())) {
                PathClass pathClass = pathObject.getPathClass();
                // set the ROI color according to the class assigned to the corresponding PathObject
                shapes.forEach(shape -> {
                    shape.setStroke(pathClass == null ? Color.YELLOW : new Color(pathClass.getColor()));
                    shape.setFill(!QPEx.getQuPath().getOverlayOptions().getFillAnnotations() ? null : pathClass == null ? ColorToolsAwt.getMoreTranslucentColor(Color.YELLOW) : ColorToolsAwt.getMoreTranslucentColor(new Color(pathClass.getColor())));
                });
                ROIWrapper roiWrapper = new ROIWrapper();
                shapes.forEach(roiWrapper::addShape);

                List<ROIWrapper> tmp;
                String pathKey = pathClass == null ? "null" : pathClass.toString();
                if(pathClassROIsMap.containsKey(pathKey)){
                    tmp = pathClassROIsMap.get(pathKey);
                }else{
                    tmp = new ArrayList<>();
                }
                tmp.add(roiWrapper);
                pathClassROIsMap.put(pathKey, tmp);
            }
        });

        pathClassROIsMap.values().forEach(omeroRois::addAll);

        return omeroRois ;
    }

    /**
     * Convert OMERO ROIs into QuPath pathObjects
     *
     * @param roiWrapperList
     * @return List of QuPath pathObjects
     */
    protected static Collection<PathObject> createPathObjectsFromOmeroROIs(List<ROIWrapper> roiWrapperList){
        Map<Double,Double> idParentIdMap = new HashMap<>();
        Map<Double,PathObject> idObjectMap = new HashMap<>();

        for (ROIWrapper roiWrapper : roiWrapperList) {
            // get the comment attached to OMERO ROIs
            List<String> roiCommentsList = getROIComment(roiWrapper);

            // check that all comments are identical for all the shapes attached to the same ROI.
            // if there are different, a warning is thrown because they should be identical.
            String roiComment = "";
            if(!roiCommentsList.isEmpty()) {
                roiComment = roiCommentsList.get(0);
                for (int i = 0; i < roiCommentsList.size() - 1; i++) {
                    if (!(roiCommentsList.get(i).equals(roiCommentsList.get(i + 1)))) {
                        logger.warn("Different classes are set for two shapes link to the same parent");
                        logger.warn("The following class will be assigned for all child object -> "+roiComment);
                    }
                }
            }

            // get the type, class, id and parent id of thu current ROI
            String[] roiCommentParsed = parseROIComment(roiComment);
            String roiType = roiCommentParsed[0];
            String roiClass = roiCommentParsed[1];
            double roiId = Double.parseDouble(roiCommentParsed[2]);
            double parentId = Double.parseDouble(roiCommentParsed[3]);

            // convert OMERO ROIs to QuPath ROIs
            ROI qpROI = OmeroRawShapes.convertOmeroROIsToQuPathROIs(roiWrapper);

            if(qpROI != null) {
                // convert QuPath ROI to QuPath Annotation or detection Object (according to type).
                idObjectMap.put(roiId, OmeroRawShapes.createPathObjectFromQuPathRoi(qpROI, roiType, roiClass));

                // populate parent map with current_object/parent ids
                idParentIdMap.put(roiId, parentId);
            }
        }

        // set the parent/child hierarchy and add objects without any parent to the final list
        List<PathObject> pathObjects = new ArrayList<>();

        idParentIdMap.keySet().forEach(objID->{
            // if the current object has a valid id and has a parent
            if(objID > 0 && idParentIdMap.get(objID) > 0 && !(idObjectMap.get(idParentIdMap.get(objID)) == null))
                idObjectMap.get(idParentIdMap.get(objID)).addChildObject(idObjectMap.get(objID));
            else
                // if no valid id for object or if the object has no parent
                pathObjects.add(idObjectMap.get(objID));
        });

        return pathObjects;
    }

    /**
     * Read the comments attach to an OMERO ROI (i.e. read each comment attached to each shape of the ROI)
     *
     * @param roiWrapper
     * @return List of comments
     */
    protected static List<String> getROIComment(ROIWrapper roiWrapper) {
        // get the ROI
        Roi omeROI = (Roi) roiWrapper.asDataObject().asIObject();

        // get the shapes contained in the ROI (i.e. holes or something else)
        List<Shape> shapes = omeROI.copyShapes();
        List<String> list = new ArrayList<>();

        // Iterate on shapes, select the correct instance and get the comment attached to it.
        for (Shape shape : shapes) {
            String comment = getROIComment(shape);
            if (comment != null)
                list.add(comment);
        }

        return list;
    }

    /**
     * Read the comment attached to one shape of an OMERO ROI.
     *
     * @param shape
     * @return The shape comment
     */
    protected static String getROIComment(Shape shape){
        if(shape instanceof Rectangle){
            RectangleData s = new RectangleData(shape);
            return s.getText();
        }else if(shape instanceof Ellipse){
            EllipseData s = new EllipseData(shape);
            return s.getText();
        }else if(shape instanceof Point){
            PointData s = new PointData(shape);
            return s.getText();
        }else if(shape instanceof Polyline){
            PolylineData s = new PolylineData(shape);
            return s.getText();
        }else if(shape instanceof Polygon){
            PolygonData s = new PolygonData(shape);
            return s.getText();
        }else if(shape instanceof Label){
            logger.warn("No ROIs created (requested label shape is unsupported)");
            //s=new TextData(shape);
        }else if(shape instanceof Line){
            LineData s = new LineData(shape);
            return s.getText();
        }else if(shape instanceof Mask){
            logger.warn("No ROIs created (requested Mask shape is not supported yet)");
            //s=new MaskData(shape);
        }else{
            logger.warn("Unsupported shape ");
        }

        return null;
    }

    /**
     * Parse the comment based on the format introduced in {OmeroRawShapes.setRoiComment(PathObject src, String objectID, String parentID)}
     *
     * @param comment
     * @return The split comment
     */
    protected static String[] parseROIComment(String comment) {
        // default parsing
        String roiClass = "NoClass";
        String roiType = "annotation";
        String roiParent =  "0";
        String roiID =  "-"+System.nanoTime();

        // split the string
        String[] tokens = (comment.isBlank() || comment.isEmpty()) ? null : comment.split(":");
        if(tokens == null)
            return new String[]{roiType, roiClass, roiID, roiParent};

        // get ROI type
        if (tokens.length > 0)
            roiType = tokens[0];

        // get the class
        if(tokens.length > 1)
            roiClass = tokens[1];

        // get the ROI id
        if(tokens.length > 2) {
            try {
                Double.parseDouble(tokens[2]);
                roiID = tokens[2];
            } catch (NumberFormatException e) {
                roiID = "-"+System.nanoTime();
            }
        }

        // get the parent ROI id
        if(tokens.length > 3) {
            try {
                Double.parseDouble(tokens[3]);
                roiParent = tokens[3];
            } catch (NumberFormatException e) {
                roiParent = "0";
            }
        }

        return new String[]{roiType, roiClass, roiID, roiParent};
    }

    /**
     * According to specificities of the shape coordinates, rectangle or polygon ROI is created
     *
     * @param polygonROICoordinates
     * @param roi
     * @return
     */
    private static ROI createROI(List<Point2> polygonROICoordinates, ROI roi){
        if (polygonROICoordinates.size() == 5) {
            if(polygonROICoordinates.get(0).getX()==polygonROICoordinates.get(1).getX() &&
               polygonROICoordinates.get(1).getY()==polygonROICoordinates.get(2).getY() &&
               polygonROICoordinates.get(2).getX()==polygonROICoordinates.get(3).getX() &&
               polygonROICoordinates.get(3).getY()==polygonROICoordinates.get(4).getY()){
                double maxX = Collections.max(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double minX = Collections.min(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double maxY = Collections.max(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));
                double minY = Collections.min(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));

                return ROIs.createRectangleROI(minX, minY, maxX-minX, maxY-minY,
                        roi.getImagePlane());
            }
            else
                return ROIs.createPolygonROI(polygonROICoordinates, roi.getImagePlane());
        }
        else{
            //TODO find a way to capture precisely if the polygon is an ellipse or not
            /*List<Double> distance = new ArrayList<>();
            double dist = 0;
            for(int i = 0; i < polygonROICoordinates.size()-1; i++){
                Point2 p1 = polygonROICoordinates.get(i);
                Point2 p2 = polygonROICoordinates.get(i+1);
                dist += p1.distance(p2);
                distance.add(p1.distance(p2));
            }
            double distMean = 0;
            AtomicReference<Double> distStd = new AtomicReference<>((double) 0);
            if (polygonROICoordinates.size() != 0) {
                distMean = dist / polygonROICoordinates.size();
                double finalDist = distMean;
                distance.forEach(c-> distStd.updateAndGet(v -> v + Math.pow(c - finalDist, 2)));
                distStd.set(Math.sqrt(distStd.get() / polygonROICoordinates.size()));
            }
            System.out.println("Std des distances   "+distStd.get());
           System.out.println("Mean des distances   "+distMean);
            if(distStd.get() < 10){
                double maxX = Collections.max(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double minX = Collections.min(polygonROICoordinates.stream().map(Point2::getX).collect(Collectors.toList()));
                double maxY = Collections.max(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));
                double minY = Collections.min(polygonROICoordinates.stream().map(Point2::getY).collect(Collectors.toList()));

                return ROIs.createEllipseROI(minX, minY, (maxX-minX), (maxY-minY), roi.getImagePlane());
            }
            else*/
                return ROIs.createPolygonROI(polygonROICoordinates, roi.getImagePlane());
        }
    }


    /**
     * @param client
     * @param roiData
     * @param owner
     * @return the list of all ROIs created by the specified owner
     */
    public static List<ROIWrapper> filterByOwner(OmeroRawClient client, List<ROIWrapper> roiData, String owner){
        List<ROIWrapper> filteredROI = new ArrayList<>();
        Map<Long, String> ownerMap = new HashMap<>();

        if(owner == null || owner.isEmpty() || owner.equals(Utils.ALL_USERS))
            return roiData;

        for(ROIWrapper roi : roiData){
            // get the ROI's owner ID
            long ownerId = roi.getOwner().getId();
            String roiOwner;

            // get the ROI's owner
            if(ownerMap.containsKey(ownerId)){
                roiOwner = ownerMap.get(ownerId);
            }else{
                ExperimenterWrapper ownerObj;
                try {
                    ownerObj = client.getSimpleClient().getUser(ownerId);
                }catch(ServiceException | OMEROServerError e){
                    Utils.errorLog(logger, "OMERO - Admin", "Impossible to retrieve owner "+ownerId, e,true);
                    continue;
                }
                roiOwner = ownerObj.getUserName();
                ownerMap.put(ownerId, roiOwner);
            }

            if(roiOwner.equals(owner))
                filteredROI.add(roi);
        }
        return filteredROI;
    }
}