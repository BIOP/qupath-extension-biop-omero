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

package qupath.ext.biop.servers.omero.raw.client;

import fr.igred.omero.Client;
import fr.igred.omero.meta.ExperimenterWrapper;
import fr.igred.omero.meta.GroupWrapper;
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
import omero.gateway.Gateway;
import omero.gateway.LoginCredentials;
import omero.gateway.SecurityContext;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ExperimenterData;
import omero.log.SimpleLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.biop.servers.omero.raw.OmeroRawExtension;
import qupath.ext.biop.servers.omero.raw.utils.OmeroRawTools;
import qupath.fx.dialogs.Dialogs;

import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


/**
 * Class representing an OMERO Raw Client. This class takes care of
 * logging in, keeping the connection alive and logging out.
 *
 * @author Olivier Burri & Rémy Dornier
 */
public class OmeroRawClient {

    final private static Logger logger = LoggerFactory.getLogger(OmeroRawClient.class);
    private int port = 4064;
    private boolean isAdminUser = false;
    private Client simpleClient;

    /**
     * List of all URIs supported by this client.
     */
    private final ObservableList<URI> uris = FXCollections.observableArrayList();

    /**
     * 'Clean' URI representing the server's URI (<b>not</b> its images). <p> See {@link OmeroRawTools#getServerURI(URI)}.
     */
    private final URI serverURI;

    /**
     * The username might be empty (public), and might also change (user switching account)
     */
    private final StringProperty username;

    /**
     * Logged in property (modified by login/loggedIn/logout/timer)
     */
    private final BooleanProperty loggedIn;

    // TODO check if we need to keep the connection alive

   public static OmeroRawClient create(URI serverURI) throws MalformedURLException, URISyntaxException {
        // Clean server URI (filter out wrong URIs and get rid of unnecessary characters)
        var cleanServerURI = new URL(serverURI.getScheme(), serverURI.getHost(), serverURI.getPort(), "").toURI();

        // Create OmeroRawClient with the serverURI
        return new OmeroRawClient(cleanServerURI);
    }

    private OmeroRawClient(final URI serverUri) {
        this.serverURI = serverUri;
        this.username = new SimpleStringProperty("");
        this.loggedIn = new SimpleBooleanProperty(false);
    }

    /*/**
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
   /*static boolean canBeAccessed(URI uri, OmeroRawObjects.OmeroRawObjectType type) throws IllegalArgumentException, ConnectException {
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
    }*/

    private boolean authenticate(final PasswordAuthentication authentication) throws Exception {
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

        SecurityContext securityContext = new SecurityContext(groupID);
        ExperimenterWrapper loggedInUser = new ExperimenterWrapper(gateway.getLoggedInUser());
        this.simpleClient = new Client(gateway, securityContext, loggedInUser);

        this.isAdminUser = loggedInUser.isAdmin(this.simpleClient);

        return gateway.isConnected();
    }


    /**
     * switch the current group to another group where the user is also part of
     *
     * @param groupId
     */
    public void switchGroup(long groupId)  {
        // check if the user is member of the group
        boolean canUserAccessGroup = this.simpleClient.getUser().getGroups().stream()
                .map(GroupWrapper::getId)
                .anyMatch(e -> e == groupId);

        // if member, change the group
        if (canUserAccessGroup || this.isAdminUser)
            this.simpleClient.switchGroup(groupId);
    }

    /**
     * Return whether the client is logged in to its server (<b>not</b> necessarily with access to all its images).
     *
     * @return isLoggedIn
     */
    public boolean isLoggedIn() { return this.simpleClient.isConnected(); }
    public ExperimenterWrapper getLoggedInUser() { return this.simpleClient.getUser(); }
    public boolean isAdmin() {
        return this.isAdminUser;
    }
    public StringProperty usernameProperty() {
        return username;
    }
    public BooleanProperty logProperty() {
        return loggedIn;
    }
    public String getUsername() {
        return username.get();
    }
    public Client getSimpleClient(){return this.simpleClient;}
    public int getPort(){return this.port;}

    private Gateway getGateway() {
        return this.simpleClient.getGateway();
    }
    private SecurityContext getContext() { return this.simpleClient.getCtx(); }

    /**
     * Return the server URI ('clean' URI) of this {@code OmeroRawClient}.
     * @return serverUri
     * @see OmeroRawTools#getServerURI(URI)
     */
    public URI getServerURI() {
        return serverURI;
    }

    /**
     * Return an unmodifiable list of all URIs using this {@code OmeroRawClient}.
     * @return list of uris
     * @see #addURI(URI)
     */
    public ObservableList<URI> getURIs() {
        return FXCollections.unmodifiableObservableList(uris);
    }

    /**
     * Add a URI to the list of this client's URIs.
     * <p>
     * Note: there is currently no equivalent 'removeURI()' method.
     * @param uri
     * @see #getURIs()
     */
    public void addURI(URI uri) {
        Platform.runLater(() -> {
            if (!uris.contains(uri))
                uris.add(uri);
            else
                logger.debug("URI already exists in the list. Ignoring operation.");
        });
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

            // get omero port
            port = OmeroAuthenticatorFX.getPort();
            boolean result = authenticate(authentication);

            Arrays.fill(authentication.getPassword(), (char)0);

            // If we have previous URIs and the the username was different
            if (uris.size() > 0 && usernameOld != null && !usernameOld.isEmpty() && !usernameOld.equals(authentication.getUserName())) {
                Dialogs.showInfoNotification("OMERO login", String.format("OMERO account switched from \"%s\" to \"%s\" for %s", usernameOld, authentication.getUserName(), serverURI));
            } else if (uris.size() == 0 || usernameOld == null || usernameOld.isEmpty())
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
        //TODO add this feature to simple-oero-client
        getGateway().closeConnector(getContext());
        this.simpleClient.disconnect();

        boolean isDone = !this.simpleClient.isConnected();

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

    /**
     * check if the current client is connected to the server
     * @return log in status
     */
    public boolean checkIfLoggedIn() {
        if(this.simpleClient == null) // if we invoke the method "createClientAndLogin" in OmeroRawExtension->createRawServerListMenu, the gateway is null
            return false;
        try {
            return getGateway().isAlive(getContext());

        } catch (DSOutOfServiceException e) {
            logger.error( e.getMessage() );
            return false;
        }
    }

    private static class OmeroAuthenticatorFX extends Authenticator {

        private String lastUsername = "";
        private static String port = "4064";

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            PasswordAuthentication authentication = getPasswordAuthentication(getRequestingPrompt(),
                    getRequestingHost(), lastUsername);
            if (authentication == null)
                return null;

            lastUsername = authentication.getUserName();
            return authentication;
        }

        static int getPort(){
            try {
                return Integer.parseInt(port);
            }catch(Exception e) {
                Dialogs.showWarningNotification("Wrong port"," Port "+port+" is not recognized as a correct port. Default port 4064 is used instead");
                port = "4064";
                return Integer.parseInt(port);
            }
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

            Label labPort = new Label("Port");
            TextField tfPort = new TextField(port);
            labPort.setLabelFor(tfPort);

            int row = 0;
            if (prompt != null && !prompt.isBlank())
                pane.add(new Label(prompt), 0, row++, 2, 1);
            pane.add(labHost, 0, row++, 2, 1);
            pane.add(labUsername, 0, row);
            pane.add(tfUsername, 1, row++);
            pane.add(labPassword, 0, row);
            pane.add(tfPassword, 1, row++);
            pane.add(labPort, 0, row);
            pane.add(tfPort, 1, row);

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

            // set omero port
            port = tfPort.getText();

            return new PasswordAuthentication(userName, password);
        }
    }
}