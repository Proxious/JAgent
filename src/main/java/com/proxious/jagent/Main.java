package com.proxious.jagent;

import org.apache.commons.codec.binary.Base64;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Main {

    private static IniFile config = null;
    private static File configFile = new File(System.getProperty("user.home") + "/config.ini");

    public static void main(String args[]) {
        Map<String, String> commands = new HashMap<>();

        try {
            if (!configFile.exists()) {
                try {
                    configFile.getParentFile().mkdirs();
                    configFile.createNewFile();

                    if (!configFile.canWrite()) {
                        configFile.setWritable(true);
                    }

                    if (!configFile.canRead()) {
                        configFile.setReadable(true);
                    }

                    List<String> lines = Arrays.asList("[configuration]", "password=", "port=", "[commands]", "[whitelisted_ips]", "[blacklisted_ips]");
                    Files.write(configFile.toPath(), lines, Charset.forName("UTF-8"));
                }
                catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            config = new IniFile(configFile.getAbsolutePath());

            StringBuilder errors = new StringBuilder();

            if (config != null) {
                if (config.hasSection("configuration")) {
                    if (!config.hasField("configuration", "port")) {
                        errors.append("No port entry found.\n");
                    }
                    else {
                        try {
                            int port = Integer.parseInt(config.getString("configuration", "port"));

                            if (port < 1 || port >= 65535) {
                                errors.append("Port entry doesn't seem to be a valid port.\n");
                            }
                        }
                        catch (NumberFormatException ex) {
                            errors.append("Port entry seems to be an invalid number.\n");
                        }
                    }

                    if (!config.hasField("configuration", "password")) {
                        errors.append("No password entry found.\n");
                    }
                }
                else {
                    errors.append("No configuration section found.\n");
                }

                if (!config.hasSection("commands")) {
                    errors.append("No commands section found.");
                }
            }
            else {
                errors.append("Error initialising config object.\n");
            }

            if (!errors.toString().isEmpty()) {
                System.out.println(errors.toString());
                System.out.println("Errors were found in your configuration file (" + configFile.getPath() + "): \n" + errors.toString());
            }
            else {
                ServerSocket ss = new ServerSocket(Integer.valueOf(config.getString("configuration", "port")));
                Socket client = null;

                if (config.hasSection("commands")) {
                    commands.putAll(config.getSection("commands"));
                }

                for (; ; ) {
                    if (config.hasSection("whitelisted_ips")) {
                        if (!config.hasField("whitelisted_ips", ss.getInetAddress().getHostName())) {
                            ss.close();
                        }
                    }
                    else if (config.hasSection("blacklisted_ips")) {
                        if (!config.hasField("blacklisted_ips", ss.getInetAddress().getHostName())) {
                            ss.close();
                        }
                    }
                    else {
                        client = ss.accept();
                    }

                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                    PrintWriter out = new PrintWriter(client.getOutputStream());

                    out.print("HTTP/1.1 200 \r\n");
                    out.print("Content-Type: application/json\r\n");
                    out.print("Connection: close\r\n");
                    out.print("\r\n");

                    String line;
                    String getRequest = null;
                    String params = null;

                    JSONObject response = new JSONObject();

                    while ((line = in.readLine()) != null) {
                        if (line.length() == 0) {
                            break;
                        }

                        if (line.contains("GET")) {
                            getRequest = line.replace("GET", "").replace("HTTP/1.1", "").replace(" ", "");
                        }

                        //out.print(line + "\r\n");
                    }

                    if (getRequest != null) {
                        Map<String, String> parameters = splitQuery(new URL("http://dummysite.com" + getRequest));

                        if (parameters.containsKey("password")) {
                            if (parameters.get("password").equals(config.getString("configuration", "password"))) {
                                response.put("password", "accepted");

                                if (parameters.containsKey("action")) {
                                    response.put("action", parameters.get("action"));
                                }

                                if (parameters.containsKey("parameters")) {
                                    if (Base64.isBase64(parameters.get("parameters"))) {
                                        params = new String(Base64.decodeBase64(parameters.get("parameters")), "UTF-8");
                                        response.put("parameters", params);
                                    }
                                }

                                if (parameters.containsKey("command")) {
                                    if (commands.containsKey(parameters.get("command"))) {
                                        Process p = Runtime.getRuntime().exec(config.getString("commands", parameters.get("command")).replace("~", System.getProperty("user.home")) + " " + params);
                                        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                                        String s;

                                        String output = null;

                                        while ((s = br.readLine()) != null) {
                                            if (output == null) {
                                                output = s + "\n";
                                            }
                                            else {
                                                output += s + "\n";
                                            }
                                        }

                                        p.waitFor();
                                        p.destroy();

                                        response.put("response", output);
                                    }
                                    else {
                                        response.put("response", "Command " + parameters.get("command") + " not found in config.");
                                    }
                                }
                            }
                            else {
                                response.put("password", "denied");
                            }
                        }
                        else {
                            response.put("password", "denied");
                        }
                    }
                    else {
                        response.put("password", "denied");
                    }

                    out.write(response.toString());

                    out.close();
                    in.close();
                    client.close();
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Map<String, String> splitQuery(URL url) {
        Map<String, String> query_pairs = new LinkedHashMap<>();
        String query = url.getQuery();

        if (query != null && query.contains("&")) {
            String[] pairs = query.split("&");

            for (String pair : pairs) {
                int idx = pair.indexOf("=");

                try {
                    query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                }
                catch (UnsupportedEncodingException e) {
                    e.printStackTrace();
                }
            }
        }

        return query_pairs;
    }
}
