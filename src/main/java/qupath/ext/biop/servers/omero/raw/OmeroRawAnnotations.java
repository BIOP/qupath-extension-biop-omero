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

package qupath.ext.biop.servers.omero.raw;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import omero.gateway.model.FileAnnotationData;
import omero.gateway.model.MapAnnotationData;
import omero.gateway.model.PermissionData;
import omero.gateway.model.RatingAnnotationData;
import omero.gateway.model.TagAnnotationData;
import omero.gateway.model.TextualAnnotationData;
import omero.model.NamedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class representing OMERO annotations.
 * <p>
 * OMERO annotations are <b>not</b> similar to QuPath annotations. Rather, they
 * represent some type of metadata visible on the right pane in the OMERO Webclient.
 *
 * Note: Tables annotations are ignored.
 *
 * @author Melvin Gelbard
 */
// TODO: Handle Table annotations
final class OmeroRawAnnotations {

    private final static Logger logger = LoggerFactory.getLogger(OmeroRawAnnotations.class);

    public enum OmeroRawAnnotationType {
        TAG("TagAnnotationI", "tag"),
        MAP("MapAnnotationI", "map"),
        ATTACHMENT("FileAnnotationI", "file"),
        COMMENT("CommentAnnotationI", "comment"),
        RATING("LongAnnotationI", "rating"),
        UNKNOWN("Unknown", "unknown");

        private final String name;
        private final String urlName;
        OmeroRawAnnotationType(String name, String urlName) {
            this.name = name;
            this.urlName = urlName;
        }

        public static OmeroRawAnnotationType fromString(String text) {
            for (var type : OmeroRawAnnotationType.values()) {
                if (type.name.equalsIgnoreCase(text) || type.urlName.equalsIgnoreCase(text))
                    return type;
            }
            return UNKNOWN;
        }

        public String toURLString() {
            return urlName;
        }

        @Override
        public String toString() {
            return name;
        }
    }


    private final List<OmeroAnnotation> annotations;

    private final List<OmeroRawObjects.Experimenter> experimenters;

    private final OmeroRawAnnotationType type;

    protected OmeroRawAnnotations(List<OmeroAnnotation> annotations, List<OmeroRawObjects.Experimenter> experimenters, OmeroRawAnnotationType type) {
        this.annotations = Objects.requireNonNull(annotations);
        this.experimenters = Objects.requireNonNull(experimenters);
        this.type = type;
    }

    /**
     * Static factory method to get all annotations & experimenters in a single {@code OmeroAnnotations} object.
     * @param client
     * @param annotationType
     * @param annotations
     * @return
     */
    public static OmeroRawAnnotations getOmeroAnnotations(OmeroRawClient client, OmeroRawAnnotationType annotationType, List<?> annotations) {
        List<OmeroAnnotation> omeroAnnotations = new ArrayList<>();
        List<OmeroRawObjects.Experimenter> experimenters = new ArrayList<>();

        switch(annotationType){
            case TAG:
                List<TagAnnotationData> tags = (List<TagAnnotationData>)annotations.stream().filter(tag-> tag instanceof TagAnnotationData).toList();
                tags.forEach(tag-> {
                    omeroAnnotations.add(new TagAnnotation(client, tag));
                    experimenters.add(new OmeroRawObjects.Experimenter(tag.getOwner().asExperimenter()));
                });
                break;
            case MAP:
                List<MapAnnotationData> kvps = (List<MapAnnotationData>)annotations.stream().filter(map-> map instanceof MapAnnotationData).toList();
                kvps.forEach(kvp-> {
                    omeroAnnotations.add(new MapAnnotation(client, kvp));
                    experimenters.add(new OmeroRawObjects.Experimenter(kvp.getOwner().asExperimenter()));
                });
                break;
            case ATTACHMENT:
                List<FileAnnotationData> files = (List<FileAnnotationData>)annotations.stream().filter(file-> file instanceof FileAnnotationData).toList();
                files.forEach(file-> {
                    omeroAnnotations.add(new FileAnnotation(client, file));
                    experimenters.add(new OmeroRawObjects.Experimenter(file.getOwner().asExperimenter()));
                });
                break;
            case RATING:
                List<RatingAnnotationData> ratings = (List<RatingAnnotationData>)annotations.stream().filter(rating-> rating instanceof RatingAnnotationData).toList();
                ratings.forEach(rating-> {
                    omeroAnnotations.add(new LongAnnotation(client, rating));
                    experimenters.add(new OmeroRawObjects.Experimenter(rating.getOwner().asExperimenter()));
                });
                break;
            case COMMENT:
                List<TextualAnnotationData> comments = (List<TextualAnnotationData>)annotations.stream().filter(comment-> comment instanceof TextualAnnotationData).toList();
                comments.forEach(comment-> {
                    omeroAnnotations.add(new CommentAnnotation(client, comment));
                    experimenters.add(new OmeroRawObjects.Experimenter(comment.getOwner().asExperimenter()));
                });
                break;

            default:

        }
        return new OmeroRawAnnotations(omeroAnnotations, experimenters, annotationType);
    }

    /**
     * Return all {@code OmeroAnnotation} objects present in this {@code OmeroAnnotation}s object.
     * @return annotations
     */
    public List<OmeroAnnotation> getAnnotations() {
        return annotations;
    }

    /**
     * Return all {@code Experimenter}s present in this {@code OmeroAnnotations} object.
     * @return experimenters
     */
    public List<OmeroRawObjects.Experimenter> getExperimenters() {
        return experimenters;
    }

    /**
     * Return the type of the {@code OmeroAnnotation} objects present in this {@code OmeroAnnotations} object.
     * @return type
     */
    public OmeroRawAnnotationType getType() {
        return type;
    }

    /**
     * Return the number of annotations in this {@code OmeroAnnotations} object.
     * @return size
     */
    public int getSize() {
        return annotations.stream().mapToInt(OmeroAnnotation::getNInfo).sum();
    }



    abstract static class OmeroAnnotation {

        private long id;
        private OmeroRawObjects.Owner owner;
        private OmeroRawObjects.Permission permissions;
        private String type;
        private OmeroRawObjects.Link link;

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
         * @param permissions
         */
        void setPermissions(OmeroRawObjects.Permission permissions) { this.permissions = permissions; }

        /**
         * Set the permissions of this object
         * @param link
         */
        void setLink(OmeroRawObjects.Link link) { this.link = link; }

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
        public OmeroRawObjects.Owner addedBy() {
            return link.getOwner();
        }

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

        public TagAnnotation(OmeroRawClient client,TagAnnotationData tag) {
            this.value = tag.getTagValue();
            super.setId(tag.getId());
            super.setType(OmeroRawAnnotationType.TAG.toString());

            omero.model.Experimenter user = tag.getOwner().asExperimenter();
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = tag.getPermissions();
            super.setPermissions(new OmeroRawObjects.Permission(permissions, client));
            super.setLink(new OmeroRawObjects.Link(owner));
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

        public MapAnnotation(OmeroRawClient client,MapAnnotationData kvp) {

            List<NamedValue> data = (List)kvp.getContent();
            this.values = new HashMap<>();
            for (NamedValue next : data) {

                this.values.put(next.name, next.value);
            }

            super.setId(kvp.getId());
            super.setType(OmeroRawAnnotationType.MAP.toString());

            omero.model.Experimenter user = kvp.getOwner().asExperimenter();
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = kvp.getPermissions();
            super.setPermissions(new OmeroRawObjects.Permission(permissions, client));
            super.setLink(new OmeroRawObjects.Link(owner));
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

        public FileAnnotation(OmeroRawClient client, FileAnnotationData file){

            this.name = file.getFileName();
            this.mimeType = file.getServerFileMimetype();
            this.size = file.getFileSize();

            super.setId(file.getId());
            super.setType(OmeroRawAnnotationType.ATTACHMENT.toString());

            omero.model.Experimenter user = file.getOwner().asExperimenter();
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = file.getPermissions();
            super.setPermissions(new OmeroRawObjects.Permission(permissions, client));
            super.setLink(new OmeroRawObjects.Link(owner));
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

        public CommentAnnotation(OmeroRawClient client, TextualAnnotationData comment){

            this.value = comment.getText();

            super.setId(comment.getId());
            super.setType(OmeroRawAnnotationType.COMMENT.toString());

            omero.model.Experimenter user = comment.getOwner().asExperimenter();
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = comment.getPermissions();
            super.setPermissions(new OmeroRawObjects.Permission(permissions, client));
            super.setLink(new OmeroRawObjects.Link(owner));
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

        public LongAnnotation(OmeroRawClient client, RatingAnnotationData rating){

            this.value = rating.getRating();

            super.setId(rating.getId());
            super.setType(OmeroRawAnnotationType.RATING.toString());

            omero.model.Experimenter user = rating.getOwner().asExperimenter();
            OmeroRawObjects.Owner owner = new OmeroRawObjects.Owner(user.getId()==null ? 0 : user.getId().getValue(),
                    user.getFirstName()==null ? "" : user.getFirstName().getValue(),
                    user.getMiddleName()==null ? "" : user.getMiddleName().getValue(),
                    user.getLastName()==null ? "" : user.getLastName().getValue(),
                    user.getEmail()==null ? "" : user.getEmail().getValue(),
                    user.getInstitution()==null ? "" : user.getInstitution().getValue(),
                    user.getOmeName()==null ? "" : user.getOmeName().getValue());
            super.setOwner(owner);

            PermissionData permissions = rating.getPermissions();
            super.setPermissions(new OmeroRawObjects.Permission(permissions, client));
            super.setLink(new OmeroRawObjects.Link(owner));
        }
    }
}


