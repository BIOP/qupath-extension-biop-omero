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

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import omero.ServerError;
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.facility.AdminFacility;
import omero.gateway.model.ExperimenterData;
import omero.log.SimpleLogger;
import omero.model.Experimenter;
import omero.model.ExperimenterGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.dialogs.Dialogs;

import javax.naming.OperationNotSupportedException;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Class representing an OMERO Web Client. This class takes care of
 * logging in, keeping the connection alive and logging out.
 *
 * @author Melvin Gelbard
 */
public class OmeroRawClient {

    final private static Logger logger = LoggerFactory.getLogger(OmeroRawClient.class);


    private SecurityContext securityContext;
    private Gateway gateway;

    // TODO Dfine port in some optional way
    private int port = 4064;

    private boolean isAdminUser = false;

    private Experimenter loggedInUser;

    /**
     * List of all URIs supported by this client.
     */
    private ObservableList<URI> uris = FXCollections.observableArrayList();

    /**
     * 'Clean' URI representing the server's URI (<b>not</b> its images). <p> See {@link OmeroRawTools#getServerURI(URI)}.
     */
    private URI serverURI;

    /**
     * The username might be empty (public), and might also change (user switching account)
     */
    private StringProperty username;

    /**
     * Logged in property (modified by login/loggedIn/logout/timer)
     */
    private BooleanProperty loggedIn;

    // TODO check if we need to keep the connection alive

    static OmeroRawClient create(URI serverURI) throws MalformedURLException, URISyntaxException {

        // Clean server URI (filter out wrong URIs and get rid of unnecessary characters)
        var cleanServerURI = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "").toURI();

        // Create OmeroRawClient with the serverURI
        OmeroRawClient client = new OmeroRawClient(cleanServerURI);

        return client;
    }

    private OmeroRawClient(final URI serverUri) {
        this.serverURI = serverUri;
        this.username = new SimpleStringProperty("");
        this.loggedIn = new SimpleBooleanProperty(false);

    }

    /**
     * Attempt to access the OMERO object given by the provided {@code uri} and {@code type}.
     * <p>
     * N.B. being logged on the server doesn't necessarily mean that the user has
     * permission to access all the objects on the server.
     * @param uri
     * @param type
     * @return success
     * @throws IllegalArgumentException
     * @throws ConnectException
     */
    static boolean canBeAccessed(URI uri, OmeroRawObjects.OmeroRawObjectType type) throws IllegalArgumentException, ConnectException {
        try {
            logger.debug("Attempting to access {}...", type.toString().toLowerCase());
            int id = OmeroRawTools.parseOmeroRawObjectId(uri, type);
            if (id == -1)
                throw new NullPointerException("No object ID found in: " + uri);

            // Implementing this as a switch because of future plates/wells/.. implementations
            String query;
            switch (type) {
                case PROJECT:
                case DATASET:
                case IMAGE:
                    query = String.format("/api/v0/m/%s/", type.toURLString());
                    break;
                case ORPHANED_FOLDER:
                case UNKNOWN:
                    throw new IllegalArgumentException();
                default:
                    throw new OperationNotSupportedException("Type not supported: " + type);
            }

            URL url = new URL(uri.getScheme(), uri.getHost(), uri.getPort(), query + id);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestMethod("GET");
            connection.setDoInput(true);
            int response = connection.getResponseCode();
            connection.disconnect();
            return response == 200;
        } catch (IOException | OperationNotSupportedException ex) {
            logger.warn("Error attempting to access OMERO object", ex.getLocalizedMessage());
            return false;
        }
    }

    boolean authenticate(final PasswordAuthentication authentication) throws Exception {
        String userName = authentication.getUserName();
        String password = String.valueOf(authentication.getPassword());

        // If the port is unset, use the default one
        if (serverURI.getPort() != -1) port = serverURI.getPort();

        //Omero Connect with credentials and simpleLogger
        LoginCredentials credentials = new LoginCredentials();

        credentials.getServer().setHost(serverURI.getHost());
        credentials.getServer().setPort(port);
        credentials.getUser().setUsername(userName);
        credentials.getUser().setPassword(password);

        SimpleLogger simpleLogger = new SimpleLogger();
        Gateway gateway = new Gateway(simpleLogger);
        gateway.connect(credentials);

        // Pick up securityContext
        ExperimenterData exp = gateway.getLoggedInUser();
        long groupID = exp.getGroupId();

        SecurityContext ctx = new SecurityContext(groupID);

        this.securityContext = ctx;
        this.gateway = gateway;

        this.isAdminUser = !this.gateway.getAdminService(this.securityContext).getCurrentAdminPrivileges().isEmpty();
        this.loggedInUser = this.gateway.getLoggedInUser().asExperimenter();

        return gateway.isConnected();
    }

    /**
     * Code adapted from Pierre Pouchin (@ppouchin) from simple-omero-client project
     * https://github.com/GReD-Clermont/simple-omero-client
     *
     * Create a new OmeroRawClient from a given username. You need to have
     * administrator rights to be allowed to connect as if you were the user defined by the given username.
     * The new OmeroRawClient has a Security Context corresponding to the username but the Gateway is identical for
     * both current and sudo OmeroRawClient
     *
     * @param currentClient
     * @return
     */
    boolean sudoConnection(OmeroRawClient currentClient) {

        // If the port is unset, use the default one
        if (serverURI.getPort() != -1) port = serverURI.getPort();

        // get the username
        String username = getSudoUsername("Enter username");

        // set the gateway as the same as the current OmeroRawClient
        this.gateway = currentClient.getGateway();

        ExperimenterData sudoUser;
        // get the OMERO user according to the username
        try {
            sudoUser = currentClient.getGateway().getFacility(AdminFacility.class).lookupExperimenter(currentClient.getContext(), username);
        } catch (DSOutOfServiceException | DSAccessException | ExecutionException e) {
            logger.error("Cannot retrieve user: " + username);
            return false;
        }

        // create the new security context corresponding to the user
        if (sudoUser != null) {
            SecurityContext context = new SecurityContext(sudoUser.getDefaultGroup().getId());
            context.setExperimenter(sudoUser);
            context.sudo();
            this.securityContext = context;
            this.username = new SimpleStringProperty(username);
            this.loggedIn = new SimpleBooleanProperty(true);
        }
        else
            this.securityContext = currentClient.getContext();

        return this.gateway.isConnected();
    }


    /**
     * Popup a small window to get the username of the user you want to import an image from, to be able to build
     * a sudo connection.
     *
     * @param prompt
     * @return the username as a string
     */
    private static String getSudoUsername(String prompt) {
        GridPane pane = new GridPane();
        javafx.scene.control.Label labUsername = new javafx.scene.control.Label("Username");
        TextField tfUsername = new TextField("");
        labUsername.setLabelFor(tfUsername);

        int row = 0;
        if (prompt != null && !prompt.isBlank())
            pane.add(new javafx.scene.control.Label(prompt), 0, row++, 2, 1);
        pane.add(labUsername, 0, row);
        pane.add(tfUsername, 1, row);

        pane.setHgap(5);
        pane.setVgap(5);

        if (!Dialogs.showConfirmDialog("Login Sudo", pane))
            return null;

        return tfUsername.getText();
    }


    /**
     * switch the current group to another group where the user is also part of
     *
     * @param groupId
     * @throws DSOutOfServiceException
     * @throws ServerError
     */
    public void switchGroup(long groupId)  {
        // check if the user is member of the group
        boolean canUserAccessGroup = OmeroRawTools.getUserOmeroGroups(this, this.loggedInUser.getId().getValue()).stream()
                .map(ExperimenterGroup::getId)
                .collect(Collectors.toList())
                .stream()
                .anyMatch(e -> e.getValue() == groupId);

        // if member, change the group
        if (canUserAccessGroup || this.isAdminUser)
            this.securityContext = new SecurityContext(groupId);
    }


    Gateway getGateway() {
        return this.gateway;
    }

    Experimenter getLoggedInUser() {
        return this.loggedInUser;
    }

    boolean getIsAdmin() {
        return this.isAdminUser;
    }

    SecurityContext getContext() { return this.securityContext; }

    StringProperty usernameProperty() {
        return username;
    }

    BooleanProperty logProperty() {
        return loggedIn;
    }

    String getUsername() {
        return username.get();
    }

    void setUsername(String newUsername) {
        username.set(newUsername);
    }

    /**
     * Return the server URI ('clean' URI) of this {@code OmeroRawClient}.
     * @return serverUri
     * @see OmeroRawTools#getServerURI(URI)
     */
    URI getServerURI() {
        return serverURI;
    }

    /**
     * Return an unmodifiable list of all URIs using this {@code OmeroRawClient}.
     * @return list of uris
     * @see #addURI(URI)
     */
    ObservableList<URI> getURIs() {
        return FXCollections.unmodifiableObservableList(uris);
    }

    /**
     * Add a URI to the list of this client's URIs.
     * <p>
     * Note: there is currently no equivalent 'removeURI()' method.
     * @param uri
     * @see #getURIs()
     */
    void addURI(URI uri) {
        Platform.runLater(() -> {
            if (!uris.contains(uri))
                uris.add(uri);
            else
                logger.debug("URI already exists in the list. Ignoring operation.");
        });
    }

    /**
     * Return whether the client is logged in to its server (<b>not</b> necessarily with access to all its images).
     *
     * @return isLoggedIn
     */
    public boolean isLoggedIn() {
        return this.gateway.isConnected();
    }

    /**
     * Log in to the client's server with optional args.
     *
     * @param args
     * @return success
     */
    public boolean logIn(String...args) {
        try {
            // TODO: Parse args to look for password (or password file - and don't store them!)
            String usernameOld = username.get();
            char[] password = null;
            List<String> cleanedArgs = new ArrayList<>();
            int i = 0;
            while (i < args.length-1) {
                String name = args[i++];
                if ("--username".equals(name) || "-u".equals(name))
                    usernameOld = args[i++];
                else if ("--password".equals(name) || "-p".equals(name)) {
                    password = args[i++].toCharArray();
                } else
                    cleanedArgs.add(name);
            }
            if (cleanedArgs.size() < args.length)
                args = cleanedArgs.toArray(String[]::new);

            PasswordAuthentication authentication;

            if (usernameOld != null && password != null) {
                logger.debug("Username & password parsed from args");
                authentication = new PasswordAuthentication(usernameOld, password);
            } else
                authentication = OmeroAuthenticatorFX.getPasswordAuthentication("Please enter your login details for OMERO server", serverURI.toString(), usernameOld);
            if (authentication == null)
                return false;

            boolean result = authenticate(authentication);

            Arrays.fill(authentication.getPassword(), (char)0);

            // If we have previous URIs and the the username was different
            if (uris.size() > 0 && !usernameOld.isEmpty() && !usernameOld.equals(authentication.getUserName())) {
                Dialogs.showInfoNotification("OMERO login", String.format("OMERO account switched from \"%s\" to \"%s\" for %s", usernameOld, authentication.getUserName(), serverURI));
            } else if (uris.size() == 0 || usernameOld.isEmpty())
                Dialogs.showInfoNotification("OMERO login", String.format("Login successful: %s(\"%s\")", serverURI, authentication.getUserName()));

            // If a browser was currently opened with this client, close it
            if (OmeroRawExtension.getOpenedRawBrowsers().containsKey(this)) {
                var oldBrowser = OmeroRawExtension.getOpenedRawBrowsers().get(this);
                oldBrowser.requestClose();
                OmeroRawExtension.getOpenedRawBrowsers().remove(this);
            }

            // If this method is called from 'project-import' thread (i.e. 'Open URI..'), 'Not on FX Appl. thread' IllegalStateException is thrown
            Platform.runLater(() -> {
                this.loggedIn.set(true);
                this.username.set(authentication.getUserName());
            });

            return true;
        } catch (Exception ex) {
            logger.error(ex.getLocalizedMessage());
            Dialogs.showErrorNotification("OMERO raw server", "Could not connect to OMERO raw server.\nCheck the following:\n- Valid credentials.\n- Access permission.\n- Correct URL.");
        }
        return false;
    }

    /**
     * Log out this client from the server.
     */
    public void logOut() {
        this.gateway.closeConnector(this.securityContext);
        this.gateway.disconnect();
        boolean isDone = !this.gateway.isConnected();

        logger.info("Disconnection successful: {}", isDone);

        if (isDone) {
            loggedIn.set(false);
            username.set("");
        } else {
            logger.error("Could not logout.");
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverURI, username);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this)
            return true;

        if (!(obj instanceof OmeroRawClient))
            return false;

        return serverURI.equals(((OmeroRawClient)obj).getServerURI()) &&
                getUsername().equals(((OmeroRawClient)obj).getUsername());
    }

    public boolean checkIfLoggedIn() {
        if(this.gateway == null) // if we invoke the method "createClientAndLogin" in OmeroRawExtension->createRawServerListMenu, the gateway is null
            return false;
        try {
            return this.gateway.isAlive(this.securityContext);

        } catch (DSOutOfServiceException e) {
            logger.error( e.getMessage() );
            return false;
        }
    }

    private static class OmeroAuthenticatorFX extends Authenticator {

        private String lastUsername = "";

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            PasswordAuthentication authentication = getPasswordAuthentication(getRequestingPrompt(),
                    getRequestingHost(), lastUsername);
            if (authentication == null)
                return null;

            lastUsername = authentication.getUserName();
            return authentication;
        }

        static PasswordAuthentication getPasswordAuthentication(String prompt, String host, String lastUsername) {
            GridPane pane = new GridPane();
            Label labHost = new Label(host);
            Label labUsername = new Label("Username");
            TextField tfUsername = new TextField(lastUsername);
            labUsername.setLabelFor(tfUsername);

            Label labPassword = new Label("Password");
            PasswordField tfPassword = new PasswordField();
            labPassword.setLabelFor(tfPassword);

            int row = 0;
            if (prompt != null && !prompt.isBlank())
                pane.add(new Label(prompt), 0, row++, 2, 1);
            pane.add(labHost, 0, row++, 2, 1);
            pane.add(labUsername, 0, row);
            pane.add(tfUsername, 1, row++);
            pane.add(labPassword, 0, row);
            pane.add(tfPassword, 1, row++);

            pane.setHgap(5);
            pane.setVgap(5);

            if (!Dialogs.showConfirmDialog("Login", pane))
                return null;

            String userName = tfUsername.getText();
            int passLength = tfPassword.getCharacters().length();
            char[] password = new char[passLength];
            for (int i = 0; i < passLength; i++) {
                password[i] = tfPassword.getCharacters().charAt(i);
            }

            return new PasswordAuthentication(userName, password);
        }
    }
}