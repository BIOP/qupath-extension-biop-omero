package qupath.ext.biop.servers.omero.raw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClient;
import qupath.ext.biop.servers.omero.raw.client.OmeroRawClients;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.ImageServerBuilder;

import java.awt.image.BufferedImage;
import java.io.IOException;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builder for ImageServers that make use of the OMERO-java library.
 *
 * @author Olivier Burri and Rémy Dornier
 *
 */
public class OmeroRawImageServerBuilder implements ImageServerBuilder<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(OmeroRawImageServerBuilder.class);

    /**
     * A list of active clients. The user may not necessarily be logged in.
     */
    final private static Map<String, OmeroRawClient> clients = new HashMap<>();

    /**
     * A set of potential hosts that don't correspond to valid OMERO web servers.
     * This is used to avoid trying again.
     */
    final private static Set<String> failedHosts = new HashSet<>();
    /**
     * Patterns for parsing input URIs
     */
    private final static Pattern patternOldViewer = Pattern.compile("/webgateway/img_detail/(\\d+)");
    private final static Pattern patternNewViewer = Pattern.compile("images=(\\d+)");
    private final static Pattern patternWebViewer = Pattern.compile("/webclient/img_detail/(\\d+)");
    private final static Pattern patternType = Pattern.compile("show=(\\w+-)");
    /**
     * Last username for login
     */
    private final String lastUsername = "";
    /**
     * Encoding differences
     */
    private final String equalSign = "%3D";
    private final String vertBarSign = "%7C";

    static boolean canConnectToOmero(URI uri, String... args) {
        try {

            if (supportLevel(uri) <= 0) {
                logger.debug("OMERO raw server does not support {}", uri);
                return false;
            }

            var serverUri = OmeroRawTools.getServerURI(uri);

            if (serverUri == null)
                return false;

            var client = OmeroRawClients.getClientFromServerURI(serverUri);
            if (client == null) {
                client = OmeroRawClient.create(serverUri);
                client.logIn(args);
            }

            if (!client.isLoggedIn())
                return false;

            // Add the client to the list (but not URI yet!)
            OmeroRawClients.addClient(client);
            return true;
        } catch (MalformedURLException | URISyntaxException e) {
            e.printStackTrace();
        }

        return false;

    }

    private static float supportLevel(URI uri, String... args) {

        String host = uri.getHost();

        // If we tried, and failed, to treat this as an OMERO server before, fail early
        if (failedHosts.contains(host))
            return 0;

        var serverUri = OmeroRawTools.getServerURI(uri);
        OmeroRawClient client = OmeroRawClients.getClientFromServerURI(serverUri);
        if (client != null)
            return 5;

        String scheme = uri.getScheme();
        if (scheme.startsWith("http")) {
            // Try to connect (but don't log in yet)
            try {
                client = OmeroRawClient.create(uri);
            } catch (Exception e) {
                failedHosts.add(host);
                logger.error("Unable to connect to OMERO server", e.getLocalizedMessage());
                return 0;
            }
            clients.put(host, client);
            return 5;
        }
        return 0;
    }

    @Override
    public ImageServer<BufferedImage> buildServer(URI uri, String... args) {
        if (canConnectToOmero(uri, args)) {
            try {
                URI serverUri = OmeroRawTools.getServerURI(uri);
                OmeroRawClient client = OmeroRawClients.getClientFromServerURI(serverUri);
                return new OmeroRawImageServer(uri, client, args);
            } catch (IOException e) {
                Dialogs.showErrorNotification("OMERO raw server", uri + " - " + e.getLocalizedMessage());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public UriImageSupport<BufferedImage> checkImageSupport(URI uri, String... args) {

        float supportLevel = supportLevel(uri, args);
        Collection<ServerBuilder<BufferedImage>> builders = new ArrayList<>();

        if (supportLevel > 0f) {
            List<URI> uris = new ArrayList<>();
            try {
                uris = getURIs(uri, args);
            } catch (IOException e) {
                Dialogs.showErrorNotification("OMERO Raw server", e.getLocalizedMessage());
            }

            for (var subURI : uris) {
                try (var server = buildServer(subURI, args)) {
                    builders.add(server.getBuilder());
                } catch (Exception e) {
                    logger.debug("Unable to create OMERO server", e.getLocalizedMessage());
                }
            }
        }

        return UriImageSupport.createInstance(this.getClass(), supportLevel, builders);
    }

    @Override
    public String getName() {
        return "OMERO raw builder";
    }

    @Override
    public String getDescription() {
        return "Image server using the OMERO ICE API";
    }

    @Override
    public Class<BufferedImage> getImageType() {
        return BufferedImage.class;
    }

    /**
     * Return a list of valid URIs from the given URI. If no valid URI can be parsed
     * from it, an IOException is thrown.
     *
     * <p>
     * E.g. "{@code /host/webclient/?show=image=4|image=5}" returns a list containing:
     * "{@code /host/webclient/?show=image=4}" and "{@code /host/webclient/?show=image=5}".
     *
     * @param uri
     * @param args
     * @return list
     * @throws IOException
     */
    private List<URI> getURIs(URI uri, String... args) throws IOException {
        List<URI> list = new ArrayList<>();
        String elemId = "image-";
        String query = uri.getQuery() != null ? uri.getQuery() : "";
        String shortPath = uri.getPath() + query;
        String equalSign = "%3D";
        Pattern[] similarPatterns = new Pattern[]{patternOldViewer, patternNewViewer, patternWebViewer};

        // Check for simpler patterns first
        for (Pattern similarPattern : similarPatterns) {
            var matcher = similarPattern.matcher(shortPath);
            if (matcher.find()) {
                elemId += matcher.group(1);
                list.add(URI.create(uri.getScheme() + "://" + uri.getHost() + "/webclient/?show" + equalSign + elemId));
                return list;
            }
        }

        // If no simple pattern was matched, check for the last possible one: /webclient/?show=
        if (shortPath.startsWith("/webclient/show")) {
            URI newURI = getStandardURI(uri);
            var patternElem = Pattern.compile("image-(\\d+)");
            var matcherElem = patternElem.matcher(newURI.toString());
            while (matcherElem.find()) {
                list.add(URI.create(uri.getScheme() + "://" + uri.getHost() + uri.getPath() + "?show" + equalSign + "image-" + matcherElem.group(1)));
            }
            return list;
        }

        // At this point, no valid URI pattern was found
        throw new IOException("URI not recognized: " + uri);
    }

    private URI getStandardURI(URI uri, String... args) throws IOException {
        if (!canConnectToOmero(uri, args))
            throw new IOException("Problem connecting to OMERO raw server");
        List<String> ids = new ArrayList<String>();

        // Identify the type of element shown (e.g. dataset)
        var type = "";
        String query = uri.getQuery() != null ? uri.getQuery() : "";

        // Because of encoding, the equal sign might not be matched when loading .qpproj file
        query = query.replace(equalSign, "=");

        // Match pattern
        var matcherType = patternType.matcher(query);
        if (matcherType.find())
            type = matcherType.group(1);
        else
            throw new IOException("URI not recognized: " + uri);

        var patternId = Pattern.compile(type + "(\\d+)");
        var matcherId = patternId.matcher(query);
        while (matcherId.find()) {
            ids.add(matcherId.group(1));
        }

        // Cascading the types to get all ('leaf') images
        StringBuilder sb = new StringBuilder(uri.getScheme() + "://" + uri.getHost() + uri.getPath() + "?show=image-");
        List<String> tempIds = new ArrayList<String>();
        // TODO: Support screen and plates
        switch (type) {
            case "screen-":
                type = "plate-";
            case "plate-":
                break;
            case "project-":
                for (String id : ids) {
                    URL request = new URL(uri.getScheme(), uri.getHost(), -1, "/api/v0/m/projects/" + id + "/datasets/");
                    /*
                    var data = OmeroImageServer.readPaginated(request);


                    for (int i = 0; i < data.size(); i++) {
                        tempIds.add(data.get(i).getAsJsonObject().get("@id").getAsString());
                    }
                    */
                }
                ids = new ArrayList<>(tempIds);
                tempIds.clear();
                type = "dataset-";

            case "dataset-":
                for (String id : ids) {
                    URL request = new URL(uri.getScheme(), uri.getHost(), -1, "/api/v0/m/datasets/" + id + "/images/");
                    /*
                    var data = OmeroWebImageServer.readPaginated(request);

                    for (int i = 0; i < data.size(); i++) {
                        tempIds.add(data.get(i).getAsJsonObject().get("@id").getAsString());
                    }
                    */
                }


                ids = new ArrayList<>(tempIds);
                tempIds.clear();
                type = "image-";

            case "image-":
                if (ids.isEmpty())
                    throw new IOException("No image found in URI: " + uri);
                for (int i = 0; i < ids.size(); i++) {
                    String imgId = (i == ids.size() - 1) ? ids.get(i) : ids.get(i) + vertBarSign + "image-";
                    sb.append(imgId);
                }
                break;
            default:
                throw new IOException("No image found in URI: " + uri);
        }

        return URI.create(sb.toString());
    }

}
