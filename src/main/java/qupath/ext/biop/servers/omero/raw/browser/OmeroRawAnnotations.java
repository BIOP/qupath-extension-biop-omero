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

package qupath.ext.biop.servers.omero.raw.browser;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import fr.igred.omero.annotations.AnnotationList;
import fr.igred.omero.annotations.FileAnnotationWrapper;
import fr.igred.omero.annotations.GenericAnnotationWrapper;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.RatingAnnotationWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.annotations.TextualAnnotationWrapper;
import omero.model.NamedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.lib.common.LogTools;

/**
 * Class representing OMERO annotations.
 * <p>
 * OMERO annotations are <b>not</b> similar to QuPath annotations. Rather, they
 * represent some type of metadata visible on the right pane in the OMERO Webclient.
 *
 * Note: Tables annotations are ignored (the Table harmonica in OMERO webclient)
 * but the OMERO.tables are still fetched.
 *
 * @author Rémy Dornier
 *
 * Based on the initial work of
 * @author Melvin Gelbard
 */
final class OmeroRawAnnotations {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawAnnotations.class);

    public enum OmeroRawAnnotationType {
        TAG("tag"),
        MAP("map"),
        ATTACHMENT("file"),
        COMMENT("comment"),
        RATING("rating"),
        UNKNOWN("unknown");

        private final String name;

        OmeroRawAnnotationType(String name) {
            this.name = name;
        }

        public static OmeroRawAnnotationType fromString(String text) {
            for (var type : OmeroRawAnnotationType.values()) {
                if (type.name.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        @Override
        public String toString() {
            return name;
        }
    }


    private final List<OmeroAnnotation> annotations;

    private final OmeroRawAnnotationType type;

    OmeroRawAnnotations(List<OmeroAnnotation> annotations, OmeroRawAnnotationType type) {
        this.annotations = Objects.requireNonNull(annotations);
        this.type = type;
    }

    /**
     * Static factory method to get all annotations & experimenters in a single {@code OmeroAnnotations} object.
     * @param annotationType
     * @param annotations
     * @return
     */
    static OmeroRawAnnotations getOmeroAnnotations(OmeroRawAnnotationType annotationType, AnnotationList annotations) {
        List<OmeroAnnotation> omeroAnnotations = new ArrayList<>();

        switch(annotationType){
            case TAG:
                List<TagAnnotationWrapper> tags = annotations.getElementsOf(TagAnnotationWrapper.class);
                tags.forEach(tag-> {omeroAnnotations.add(new TagAnnotation(tag));});
                break;
            case MAP:
                List<MapAnnotationWrapper> kvps = annotations.getElementsOf(MapAnnotationWrapper.class);
                kvps.forEach(kvp-> {omeroAnnotations.add(new MapAnnotation(kvp));});
                break;
            case ATTACHMENT:
                List<FileAnnotationWrapper> files = annotations.getElementsOf(FileAnnotationWrapper.class);
                files.forEach(file-> {omeroAnnotations.add(new FileAnnotation(file));});
                break;
            case RATING:
                List<RatingAnnotationWrapper> ratings = annotations.getElementsOf(RatingAnnotationWrapper.class);
                ratings.forEach(rating-> {omeroAnnotations.add(new LongAnnotation(rating));});
                break;
            case COMMENT:
                List<TextualAnnotationWrapper> comments = annotations.getElementsOf(TextualAnnotationWrapper.class);
                comments.forEach(comment-> {omeroAnnotations.add(new CommentAnnotation(comment));});
                break;
            default:

        }
        return new OmeroRawAnnotations(omeroAnnotations, annotationType);
    }

    /**
     * Return all {@code OmeroAnnotation} objects present in this {@code OmeroAnnotation}s object.
     * @return annotations
     */
    public List<OmeroAnnotation> getAnnotations() {return annotations;}

    /**
     * Return the type of the {@code OmeroAnnotation} objects present in this {@code OmeroAnnotations} object.
     * @return type
     */
    public OmeroRawAnnotationType getType() {return type;}

    /**
     * Return the number of annotations in this {@code OmeroAnnotations} object.
     * @return size
     */
    public int getSize() {return annotations.stream().mapToInt(OmeroAnnotation::getNInfo).sum();}



    abstract static class OmeroAnnotation {
        private long id;
        private OmeroRawObjects.Owner owner;
        private String type;
        //TODO See how to get the user who added the annotation, if it is possible to get it
        //private OmeroRawObjects.Link link;

        /**
         * Set the id of this object
         * @param id
         */
        void setId(long id) {
            this.id = id;
        }

        /**
         * Set the owner of this object
         * @param owner
         */
        void setOwner(OmeroRawObjects.Owner owner) {
            this.owner = owner;
        }

        /**
         * Set the type of this object
         * @param type
         */
        void setType(String type) {
            this.type = OmeroRawAnnotationType.fromString(type).toString();
        }

        /**
         * Set the permissions of this object
         * @param link
         */
        //void setLink(OmeroRawObjects.Link link) { this.link = link; }

        /**
         * Return the {@code OmeroAnnotationType} of this {@code OmeroAnnotation} object.
         * @return omeroAnnotationType
         */
        public OmeroRawAnnotationType getType() {
            return OmeroRawAnnotationType.fromString(type);
        }


        /**
         * Return the owner of this {@code OmeroAnnotation}. Which is the creator of this annotation
         * but not necessarily the person that added it.
         * @return creator of this annotation
         */
        public OmeroRawObjects.Owner getOwner() {
            return owner;
        }

        /**
         * Return the {@code Owner} that added this annotation. This is <b>not</b> necessarily
         * the same as the owner <b>of</b> the annotation.
         * @return owner who added this annotation
         */
       /* public OmeroRawObjects.Owner addedBy() {
            return link.getOwner();
        }*/

        /**
         * Return the number of 'fields' within this {@code OmeroAnnotation}.
         * @return number of fields
         */
        public int getNInfo() {
            return 1;
        }
    }

    /**
     * 'Tags'
     */
    static class TagAnnotation extends OmeroAnnotation {

        private final String value;

        protected String getValue() {
            return value;
        }

        public TagAnnotation(TagAnnotationWrapper tag) {
            this.value = tag.getName();
            super.setId(tag.getId());
            super.setType(OmeroRawAnnotationType.TAG.toString());
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(tag.getOwner());
            super.setOwner(owner);
            //super.setLink(new OmeroRawObjects.Link(owner));
        }
}

    /**
     * 'Key-Value Pairs'
     */
    static class MapAnnotation extends OmeroAnnotation {

        private final Map<String, String> values;
        protected Map<String, String> getValues() {
            return values;
        }

        @Override
        public int getNInfo() {
            return values.size();
        }

        public MapAnnotation(MapAnnotationWrapper kvp) {

            List<NamedValue> data = kvp.getContent();
            this.values = new HashMap<>();
            for (NamedValue next : data) {
                this.values.put(next.name, next.value);
            }

            super.setId(kvp.getId());
            super.setType(OmeroRawAnnotationType.MAP.toString());

            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(kvp.getOwner());
            super.setOwner(owner);
            //super.setLink(new OmeroRawObjects.Link(owner));
        }
    }


    /**
     * 'Attachments'
     */
    static class FileAnnotation extends OmeroAnnotation {

        private final String name;
        private final String mimeType;
        private final long size;

        protected String getFilename() {
            return this.name;
        }

        /**
         * Size in bits.
         * @return size
         */
        protected long getFileSize() { return this.size; }

        protected String getMimeType() {
            return this.mimeType;
        }

        public FileAnnotation(FileAnnotationWrapper file){

            this.name = file.getFileName();
            this.mimeType = file.getServerFileMimetype();
            this.size = file.getFileSize();

            super.setId(file.getId());
            super.setType(OmeroRawAnnotationType.ATTACHMENT.toString());

            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(file.getOwner());
            super.setOwner(owner);
            //super.setLink(new OmeroRawObjects.Link(owner));
        }
    }

    /**
     * 'Comments'
     */
    static class CommentAnnotation extends OmeroAnnotation {

        private final String value;

        protected String getValue() {
            return value;
        }

        public CommentAnnotation(TextualAnnotationWrapper comment){

            this.value = comment.getText();

            super.setId(comment.getId());
            super.setType(OmeroRawAnnotationType.COMMENT.toString());

            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(comment.getOwner());
            super.setOwner(owner);
            //super.setLink(new OmeroRawObjects.Link(owner));
        }


    }


    /**
     * 'Ratings'
     */
    static class LongAnnotation extends OmeroAnnotation {

        private final int value;

        protected int getValue() {
            return value;
        }

        public LongAnnotation(RatingAnnotationWrapper rating){

            this.value = rating.getRating();

            super.setId(rating.getId());
            super.setType(OmeroRawAnnotationType.RATING.toString());

            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(rating.getOwner());
            super.setOwner(owner);
            //super.setLink(new OmeroRawObjects.Link(owner));
        }
    }

    /*
     *
     *
     *                                           Deprecated methods
     *
     *
     */

    /**
     * Static factory method to get all annotations & experimenters in a single {@code OmeroAnnotations} object.
     * @param client
     * @param annotationType
     * @param annotations
     * @return
     * @deprecated use {@link OmeroRawAnnotations#getOmeroAnnotations(OmeroRawAnnotationType, AnnotationList)} instead
     */
    @Deprecated
    public static OmeroRawAnnotations getOmeroAnnotations(OmeroRawClient client, OmeroRawAnnotationType annotationType, List<?> annotations) {
        LogTools.warnOnce(logger, "getOmeroAnnotations(OmeroRawClient, OmeroRawAnnotationType, List) is deprecated - " +
                "use getOmeroAnnotations(OmeroRawAnnotationType, AnnotationList) instead");
        AnnotationList annotationList = new AnnotationList();
        annotationList.addAll((Collection<GenericAnnotationWrapper<?>>)annotations);
        return getOmeroAnnotations(annotationType, annotationList);
    }

}


