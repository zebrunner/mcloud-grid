package com.zebrunner.mcloud.grid.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides the secrets of capabilities the hub shows to anyone who reaches it: the personal STF token
 * (zebrunner:STF_TOKEN), the remote ADB address of the device (remoteURL), passwords, keys and the credentials of URLs.
 */
public final class SecretMasking {
    public static final String MASK = "*****";

    // the names of secrets, without the vendor prefix: a bare 'key' is not one (e.g. 'unicodeKeyboard')
    private static final Pattern SECRET_NAME = Pattern.compile(
            "token|passw|secret|credential|signature|apikey|api_key|accesskey|access_key|privatekey|private_key|authorization|cookie");
    private static final String[] SECRET_NAMES = {"remoteurl", "sig"};
    // user:password@ of a URL
    private static final Pattern URL_USER_INFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/?#@\\s]+@");
    // a query parameter with the name of a secret, e.g. ?token=... or &X-Amz-Signature=...
    private static final Pattern URL_SECRET_PARAMETER = Pattern.compile("([?&][^=&#\\s]+=)([^&#\\s]*)");
    // the registration token of a node and the STF token of a session in a log message: JSON (the registration request
    // Selenium logs with -debug), a map or the capabilities of a session request (Capabilities {zebrunner:STF_TOKEN: ...})
    private static final Pattern LOG_SECRET = Pattern.compile("((?:" + NodeRegistrationToken.CUSTOM_KEY + "|STF_TOKEN)\"?\\s*[:=]\\s*\"?)[^\",}\\s]+");

    private SecretMasking() {
        //hide
    }

    public static boolean isSecretName(String name) {
        if (name == null) {
            return false;
        }
        String bare = name.substring(name.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        for (String secret : SECRET_NAMES) {
            if (bare.equals(secret)) {
                return true;
            }
        }
        return SECRET_NAME.matcher(bare).find();
    }

    /**
     * @return a copy of the capabilities with the values of secret names and the credentials of URLs masked, nested maps and lists too
     */
    public static Map<String, Object> mask(Map<String, ?> capabilities) {
        Map<String, Object> masked = new LinkedHashMap<>();
        capabilities.forEach((name, value) -> masked.put(name, value != null && isSecretName(name) ? MASK : maskValue(value)));
        return masked;
    }

    @SuppressWarnings("unchecked")
    private static Object maskValue(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            ((Map<Object, Object>) value).forEach((name, nested) -> map.put(String.valueOf(name), nested));
            return mask(map);
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object nested : (List<Object>) value) {
                list.add(maskValue(nested));
            }
            return list;
        }
        if (value instanceof String) {
            return maskUrl((String) value);
        }
        return value;
    }

    /**
     * @return the log message with the registration tokens and the STF tokens masked
     */
    public static String maskLogMessage(String message) {
        if (message == null || !message.contains(NodeRegistrationToken.CUSTOM_KEY) && !message.contains("STF_TOKEN")) {
            return message;
        }
        return LOG_SECRET.matcher(message).replaceAll("$1" + MASK);
    }

    /**
     * @return the text with the user info of its URLs and their query parameters of secret names masked
     */
    public static String maskUrl(String text) {
        if (text.indexOf("://") < 0) {
            return text;
        }
        String masked = URL_USER_INFO.matcher(text).replaceAll("$1" + MASK + "@");
        Matcher parameter = URL_SECRET_PARAMETER.matcher(masked);
        StringBuilder result = new StringBuilder();
        while (parameter.find()) {
            String name = parameter.group(1).substring(1, parameter.group(1).length() - 1);
            String replacement = isSecretName(name) ? parameter.group(1) + MASK : parameter.group();
            parameter.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        parameter.appendTail(result);
        return result.toString();
    }
}
