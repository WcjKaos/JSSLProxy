package com.sslproxy.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Command-line client for JSSLProxy management APIs.
 */
public final class AdminCli {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int OK = 0;
    private static final int ARG_ERROR = 2;
    private static final int AUTH_ERROR = 3;
    private static final int UNAVAILABLE = 4;
    private static final int SERVER_ERROR = 5;

    private AdminCli() {
    }

    public static void main(String[] args) {
        System.exit(execute(args, System.out, System.err, System.getenv()));
    }

    public static int execute(String[] args, PrintStream out, PrintStream err, Map<String, String> env) {
        Options options = null;
        try {
            options = Options.parse(args, env);
            if (options.help || options.positionals.isEmpty()) {
                printHelp(out);
                return OK;
            }
            AdminHttpClient client = new AdminHttpClient(options.url, options.token,
                    Duration.ofSeconds(options.requestTimeoutSeconds));
            return run(options, client, out, err);
        } catch (CliException e) {
            err.println(e.getMessage());
            return e.exitCode;
        } catch (IllegalArgumentException e) {
            err.println("Invalid argument: " + sanitize(e.getMessage(), options == null ? null : options.token));
            return ARG_ERROR;
        }
    }

    private static int run(Options options, AdminHttpClient client, PrintStream out, PrintStream err)
            throws CliException {
        String command = optionAt(options, 0);
        return switch (command) {
            case "status" -> status(client, options, out, err);
            case "stats" -> getAdmin(client, options, out, err, "/admin/stats", "stats");
            case "certs" -> getAdmin(client, options, out, err, "/admin/certs", "certs");
            case "rejected-certs" -> getAdmin(client, options, out, err,
                    "/admin/rejected-certs?limit=" + options.limit, "rejected-certs");
            case "metrics" -> metrics(client, options, out, err);
            case "blacklist", "whitelist" -> certificateList(command, client, options, out, err);
            case "reload" -> reload(client, options, out, err);
            case "drain" -> drain(client, options, out, err);
            case "connections" -> connections(client, options, out, err);
            default -> throw new CliException(ARG_ERROR, "Unknown command: " + command);
        };
    }

    private static int status(AdminHttpClient client, Options options, PrintStream out, PrintStream err)
            throws CliException {
        requireToken(options);
        JsonObject result = new JsonObject();
        result.add("health", responseObject(send(client, "GET", "/health", null, false, err, true)));
        result.add("ready", responseObject(send(client, "GET", "/ready", null, false, err, true)));
        AdminHttpClient.AdminResponse info = send(client, "GET", "/admin/info", null, true, err, false);
        if (!info.isSuccessful()) {
            return printError(info, options, err);
        }
        result.add("info", responseObject(info));
        printJsonOrHuman(options, out, "status", result);
        return OK;
    }

    private static int getAdmin(AdminHttpClient client, Options options, PrintStream out, PrintStream err,
                                String path, String label) throws CliException {
        requireToken(options);
        return printResponse(send(client, "GET", path, null, true, err, false), options, out, err, label);
    }

    private static int metrics(AdminHttpClient client, Options options, PrintStream out, PrintStream err)
            throws CliException {
        AdminHttpClient.AdminResponse response = send(client, "GET", "/metrics", null, false, err, false);
        if (!response.isSuccessful()) {
            return printError(response, options, err);
        }
        if (options.json) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("metrics", response.body());
            out.println(GSON.toJson(wrapper));
        } else {
            out.print(response.body());
        }
        return OK;
    }

    private static int certificateList(String type, AdminHttpClient client, Options options, PrintStream out,
                                       PrintStream err) throws CliException {
        requireToken(options);
        if (options.positionals.size() < 2) {
            throw new CliException(ARG_ERROR, "Usage: " + type + " list|add|remove [fingerprint]");
        }
        String action = optionAt(options, 1);
        return switch (action) {
            case "list" -> printResponse(send(client, "GET", "/admin/" + type, null, true, err, false),
                    options, out, err, type);
            case "add" -> writeFingerprint(client, options, out, err, type, "POST");
            case "remove" -> writeFingerprint(client, options, out, err, type, "DELETE");
            default -> throw new CliException(ARG_ERROR, "Unknown " + type + " action: " + action);
        };
    }

    private static int writeFingerprint(AdminHttpClient client, Options options, PrintStream out, PrintStream err,
                                        String type, String method) throws CliException {
        requireYes(options, type + " write");
        if (options.positionals.size() < 3) {
            throw new CliException(ARG_ERROR, "Missing fingerprint");
        }
        String fingerprint = options.positionals.get(2).trim();
        requireFingerprint(fingerprint);
        return printAuditedResponse(send(client, method, "/admin/" + type, fingerprint, true, err, false),
                options, out, err, type);
    }

    private static int reload(AdminHttpClient client, Options options, PrintStream out, PrintStream err)
            throws CliException {
        requireToken(options);
        requireYes(options, "reload");
        String target = options.positionals.size() > 1 ? optionAt(options, 1) : "all";
        if (!List.of("config", "certs", "all").contains(target)) {
            throw new CliException(ARG_ERROR, "Reload target must be config, certs, or all");
        }
        return printAuditedResponse(send(client, "POST", "/admin/reload?target=" + target, null, true, err, false),
                options, out, err, "reload");
    }

    private static int drain(AdminHttpClient client, Options options, PrintStream out, PrintStream err)
            throws CliException {
        requireToken(options);
        if (options.positionals.size() < 2) {
            throw new CliException(ARG_ERROR, "Usage: drain start|cancel [--drain-timeout seconds] --yes");
        }
        String action = optionAt(options, 1);
        requireYes(options, "drain " + action);
        if ("start".equals(action)) {
            String path = options.drainTimeoutSeconds == null
                    ? "/admin/drain"
                    : "/admin/drain?timeout=" + options.drainTimeoutSeconds;
            return printAuditedResponse(send(client, "POST", path, null, true, err, false),
                    options, out, err, "drain");
        }
        if ("cancel".equals(action)) {
            return printAuditedResponse(send(client, "DELETE", "/admin/drain", null, true, err, false),
                    options, out, err, "drain");
        }
        throw new CliException(ARG_ERROR, "Unknown drain action: " + action);
    }

    private static int connections(AdminHttpClient client, Options options, PrintStream out, PrintStream err)
            throws CliException {
        requireToken(options);
        if (options.positionals.size() < 2) {
            throw new CliException(ARG_ERROR, "Usage: connections list|terminate [fingerprint]");
        }
        String action = optionAt(options, 1);
        if ("list".equals(action)) {
            return printResponse(send(client, "GET", "/admin/connections", null, true, err, false),
                    options, out, err, "connections");
        }
        if ("terminate".equals(action)) {
            requireYes(options, "connections terminate");
            if (options.positionals.size() < 3) {
                throw new CliException(ARG_ERROR, "Missing fingerprint");
            }
            String fingerprint = options.positionals.get(2).trim();
            requireFingerprint(fingerprint);
            String path = "/admin/connections?fingerprint=" + encodeQueryValue(fingerprint);
            return printAuditedResponse(send(client, "DELETE", path, null, true, err, false),
                    options, out, err, "connections");
        }
        throw new CliException(ARG_ERROR, "Unknown connections action: " + action);
    }

    private static AdminHttpClient.AdminResponse send(AdminHttpClient client, String method, String path,
                                                      String body, boolean authenticated, PrintStream err,
                                                      boolean allowAnyStatus) throws CliException {
        try {
            AdminHttpClient.AdminResponse response = client.request(method, path, body, authenticated);
            if (!allowAnyStatus && response.statusCode() >= 500) {
                return response;
            }
            return response;
        } catch (HttpTimeoutException | ConnectException e) {
            throw new CliException(UNAVAILABLE, "Management service unavailable: " + sanitize(e.getMessage()));
        } catch (IOException e) {
            throw new CliException(UNAVAILABLE, "Management request failed: " + sanitize(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(UNAVAILABLE, "Management request interrupted");
        }
    }

    private static int printResponse(AdminHttpClient.AdminResponse response, Options options, PrintStream out,
                                     PrintStream err, String label) {
        if (!response.isSuccessful()) {
            return printError(response, options, err);
        }
        printJsonOrHuman(options, out, label, parseBody(response.body()));
        return OK;
    }

    private static int printAuditedResponse(AdminHttpClient.AdminResponse response, Options options, PrintStream out,
                                            PrintStream err, String label) {
        int code = printResponse(response, options, out, err, label);
        if (code == OK && !options.json) {
            out.println("Audit: management API records this operation.");
        }
        return code;
    }

    private static int printError(AdminHttpClient.AdminResponse response, Options options, PrintStream err) {
        err.println("HTTP " + response.statusCode() + ": " + sanitize(response.body(), options.token));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return AUTH_ERROR;
        }
        if (response.statusCode() == 400 || response.statusCode() == 404 || response.statusCode() == 405) {
            return ARG_ERROR;
        }
        if (response.statusCode() >= 500) {
            return SERVER_ERROR;
        }
        return UNAVAILABLE;
    }

    private static void printJsonOrHuman(Options options, PrintStream out, String label, JsonElement body) {
        if (options.json) {
            out.println(GSON.toJson(body));
            return;
        }
        out.println(label + ":");
        out.println(GSON.toJson(body));
    }

    private static JsonElement responseObject(AdminHttpClient.AdminResponse response) {
        JsonObject object = new JsonObject();
        object.addProperty("statusCode", response.statusCode());
        object.add("body", parseBody(response.body()));
        return object;
    }

    private static JsonElement parseBody(String body) {
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(body);
        } catch (Exception ignored) {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("text", body);
            return wrapper;
        }
    }

    private static String optionAt(Options options, int index) {
        return options.positionals.get(index).toLowerCase(Locale.ROOT);
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireToken(Options options) throws CliException {
        if (options.token == null || options.token.isBlank()) {
            throw new CliException(ARG_ERROR,
                    "Admin token required. Set JSSLPROXY_ADMIN_TOKEN or pass --token.");
        }
    }

    private static void requireYes(Options options, String operation) throws CliException {
        if (!options.yes) {
            throw new CliException(ARG_ERROR, operation + " requires --yes");
        }
    }

    private static void requireFingerprint(String value) throws CliException {
        String stripped = value == null ? "" : value.replace(":", "").replace("-", "");
        if (stripped.length() != 64 || !stripped.matches("[A-Fa-f0-9]+")) {
            throw new CliException(ARG_ERROR, "Fingerprint must be a SHA-256 hex string");
        }
    }

    private static String sanitize(String value) {
        return sanitize(value, null);
    }

    private static String sanitize(String value, String token) {
        if (value == null) {
            return "";
        }
        String sanitized = value.replaceAll("(?i)(bearer\\s+)[^\\s]+", "$1<redacted>")
                .replaceAll("(?i)(token=)[^\\s&]+", "$1<redacted>");
        if (token != null && !token.isBlank()) {
            sanitized = sanitized.replace(token, "<redacted>");
        }
        return sanitized;
    }

    private static void printHelp(PrintStream out) {
        out.println("jsslproxyctl - JSSLProxy admin API client");
        out.println();
        out.println("Usage:");
        out.println("  jsslproxyctl [--url URL] [--token TOKEN] [--json] <command>");
        out.println("  java -jar JSSLProxy-1.0-SNAPSHOT.jar ctl [options] <command>");
        out.println();
        out.println("Global options:");
        out.println("  --url URL        Management URL, default JSSLPROXY_ADMIN_URL or http://localhost:9090");
        out.println("  --token TOKEN    Admin token, default JSSLPROXY_ADMIN_TOKEN");
        out.println("  --timeout SEC    Request timeout seconds");
        out.println("  --drain-timeout SEC");
        out.println("                  Drain-mode auto-cancel timeout seconds");
        out.println("  --json           Print machine-readable JSON");
        out.println("  --yes            Confirm write or dangerous operations");
        out.println("  -h, --help       Show this help");
        out.println();
        out.println("Commands:");
        out.println("  status");
        out.println("  stats");
        out.println("  certs");
        out.println("  rejected-certs [--limit N]");
        out.println("  metrics");
        out.println("  blacklist list|add|remove <fingerprint>");
        out.println("  whitelist list|add|remove <fingerprint>");
        out.println("  reload config|certs|all --yes");
        out.println("  drain start [--drain-timeout SEC] --yes");
        out.println("  drain cancel --yes");
        out.println("  connections list");
        out.println("  connections terminate <fingerprint> --yes");
    }

    private static final class Options {
        private String url;
        private String token;
        private int requestTimeoutSeconds = 10;
        private Integer drainTimeoutSeconds;
        private boolean json;
        private boolean yes;
        private boolean help;
        private int limit = 50;
        private final List<String> positionals = new ArrayList<>();

        private static Options parse(String[] args, Map<String, String> env) throws CliException {
            Options options = new Options();
            options.url = env.getOrDefault("JSSLPROXY_ADMIN_URL", "http://localhost:9090");
            options.token = env.get("JSSLPROXY_ADMIN_TOKEN");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--url" -> options.url = requireValue(args, ++i, "--url");
                    case "--token" -> options.token = requireValue(args, ++i, "--token");
                    case "--timeout" -> options.requestTimeoutSeconds =
                            parsePositiveInt(requireValue(args, ++i, "--timeout"), "--timeout");
                    case "--drain-timeout" -> options.drainTimeoutSeconds =
                            parseNonNegativeInt(requireValue(args, ++i, "--drain-timeout"), "--drain-timeout");
                    case "--limit" -> options.limit = Math.max(1,
                            Math.min(500, parsePositiveInt(requireValue(args, ++i, "--limit"), "--limit")));
                    case "--json" -> options.json = true;
                    case "--yes" -> options.yes = true;
                    case "-h", "--help" -> options.help = true;
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new CliException(ARG_ERROR, "Unknown option: " + arg);
                        }
                        options.positionals.add(arg);
                    }
                }
            }
            return options;
        }

        private static String requireValue(String[] args, int index, String option) throws CliException {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new CliException(ARG_ERROR, option + " requires a value");
            }
            return args[index];
        }

        private static int parsePositiveInt(String value, String option) throws CliException {
            int parsed = parseInt(value, option);
            if (parsed <= 0) {
                throw new CliException(ARG_ERROR, option + " must be a positive integer");
            }
            return parsed;
        }

        private static int parseNonNegativeInt(String value, String option) throws CliException {
            int parsed = parseInt(value, option);
            if (parsed < 0) {
                throw new CliException(ARG_ERROR, option + " must be a non-negative integer");
            }
            return parsed;
        }

        private static int parseInt(String value, String option) throws CliException {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new CliException(ARG_ERROR, option + " must be an integer");
            }
        }
    }

    private static final class CliException extends Exception {
        private final int exitCode;

        private CliException(int exitCode, String message) {
            super(message);
            this.exitCode = exitCode;
        }
    }
}
