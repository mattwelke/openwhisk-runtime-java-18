package com.mattwelke.owr.java18;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mattwelke.owr.java.Action;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Implements the OpenWhisk proxy contact to create a runtime.
 * Based on https://github.com/mattwelke/openwhisk-runtime-java/blob/main/core/java8/proxy/src/main/java/org/apache/openwhisk/runtime/java/action/Proxy.java
 */
public class Proxy {

    private HttpServer server;

    private JarLoader loader = null;

    private Action userAction = null;

    public Proxy(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), -1);

        this.server.createContext("/init", new InitHandler());
        this.server.createContext("/run", new RunHandler());
        this.server.setExecutor(null); // creates a default executor
    }

    public void start() {
        server.start();
    }

    private static void writeResponse(HttpExchange t, int code, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(code, bytes.length);
        OutputStream os = t.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private static void writeError(HttpExchange t, String errorMessage) throws IOException {
        JsonObject message = new JsonObject();
        message.addProperty("error", errorMessage);
        writeResponse(t, 502, message.toString());
    }

    private static void writeLogMarkers() {
        System.out.println("XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
        System.err.println("XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX");
        System.out.flush();
        System.err.flush();
    }

    private class InitHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader != null) {
                String errorMessage = "Cannot initialize the action more than once.";
                System.err.println(errorMessage);
                Proxy.writeError(t, errorMessage);
                return;
            }

            try {
                InputStream is = t.getRequestBody();
                // TODO: Rewrite code using deprecated Gson methods
                JsonParser parser = new JsonParser();
                JsonElement ie = parser.parse(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)));
                JsonObject inputObject = ie.getAsJsonObject();

                if (inputObject.has("value")) {
                    JsonObject message = inputObject.getAsJsonObject("value");
                    if (message.has("main") && message.has("code")) {
                        String mainClass = message.getAsJsonPrimitive("main").getAsString();
                        String base64Jar = message.getAsJsonPrimitive("code").getAsString();

                        // FIXME: this is obviously not very useful. The idea is that we
                        // will implement/use a streaming parser for the incoming JSON object so that we
                        // can stream the contents of the jar straight to a file.
                        InputStream jarIs = new ByteArrayInputStream(base64Jar.getBytes(StandardCharsets.UTF_8));

                        // Save the bytes to a file.
                        Path jarPath = JarLoader.saveBase64EncodedFile(jarIs);

                        // Start up the custom classloader. This also checks that the
                        // main method exists.
                        loader = new JarLoader(jarPath, mainClass);

                        Proxy.writeResponse(t, 200, "OK");
                        return;
                    }
                }

                Proxy.writeError(t, "Missing main/no code to execute.");
                return;
            } catch (Exception e) {
                e.printStackTrace(System.err);
                writeLogMarkers();
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
                return;
            }
        }
    }

    private class RunHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            if (loader == null) {
                Proxy.writeError(t, "Cannot invoke an uninitialized action.");
                return;
            }

            ClassLoader cl = Thread.currentThread().getContextClassLoader();

            try {
                var reader = new BufferedReader(new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8));
                Map<String, Object> body = new Gson().fromJson(reader, Map.class);

                // Use the "value" property as the input object, which will be the first map passed to the user code.
                Map<String, Object> value = (Map<String, Object>) body.get("value");

                // Remove "value" so that the map can be repurposed as the OpenWhisk variables map which will be the
                // second map passed to the user code.
                body.remove("value");
                Map<String, Object> owVars = body;

                Thread.currentThread().setContextClassLoader(loader);

                // User code starts running here.
                userAction.setClusterContext(owVars);
                Map<String, Object> output = userAction.invoke(value);
                // User code finished running here.

                if (output == null) {
                    throw new NullPointerException("The action returned null");
                }

                Proxy.writeResponse(t, 200, new Gson().toJson(output));
            } catch (Exception e) {
                e.printStackTrace(System.err);
                Proxy.writeError(t, "An error has occurred (see logs for details): " + e);
            } finally {
                writeLogMarkers();
                Thread.currentThread().setContextClassLoader(cl);
            }
        }
    }

    /**
     * Custom JAR loader.
     * Based on https://github.com/apache/openwhisk-runtime-java/blob/master/core/java8/proxy/src/main/java/org/apache/openwhisk/runtime/java/action/JarLoader.java
     */
    private class JarLoader extends URLClassLoader {
        public static Path saveBase64EncodedFile(InputStream encoded) throws Exception {
            Base64.Decoder decoder = Base64.getDecoder();

            InputStream decoded = decoder.wrap(encoded);

            File destinationFile = File.createTempFile("useraction", ".jar");
            destinationFile.deleteOnExit();
            Path destinationPath = destinationFile.toPath();

            Files.copy(decoded, destinationPath, StandardCopyOption.REPLACE_EXISTING);

            return destinationPath;
        }

        public JarLoader(Path jarPath, String actionClassName)
                throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, SecurityException,
                InvocationTargetException, InstantiationException, IllegalAccessException {

            super(new URL[] { jarPath.toUri().toURL() });

            // Use reflection to create an instance of the user's action.
            Class<? extends Action> actionClass = loadClass(actionClassName).asSubclass(Action.class);
            Constructor<? extends Action> actionClassConstructor = actionClass.getConstructor();

            // Associate action instance with Proxy instance so that it can be used in the run handler.
            Proxy.this.userAction = actionClassConstructor.newInstance();
        }
    }

    public static void main(String[] args) throws Exception {
        Proxy proxy = new Proxy(8080);
        proxy.start();
    }
}
