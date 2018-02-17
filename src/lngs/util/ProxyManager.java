// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.

package lngs.util;

import java.net.Authenticator;

public class ProxyManager {
    public ProxyManager() {
        proxyHost = "";
        proxyPort = "";
    }

    public boolean isEnabled() {
        return enableProxyAuthentication;
    }

    public void setEnabled(boolean enabled) {
        enableProxyAuthentication = enabled;
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost;
    }

    public void setProxyPort(String proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public String getProxyPort() {
        return proxyPort;
    }
    
    public int getProxyPortAsInt() {
        return Integer.parseInt(proxyPort);
    }

    public void enableProxyAuthentication(boolean enableProxyAuthentication) {
        this.enableProxyAuthentication = enableProxyAuthentication;
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword;
    }

    public void setProxyUser(String proxyUser) {
        this.proxyUser = proxyUser;
    }

    public String getProxyUser() {
        return proxyUser;
    }

    public String getProxyPassword() {
        return proxyPassword;
    }

    public void activateNow() {
        if (enableProxyAuthentication) {
            Authenticator.setDefault(new ProxyAuthenticator(getProxyUser(), getProxyPassword()));
        }

        System.getProperties().put("proxySet", "true");
        System.getProperties().put("proxyHost", proxyHost);
        System.getProperties().put("proxyPort", proxyPort);
    }

    public void deactivateNow() {
        if (enableProxyAuthentication) {
            Authenticator.setDefault(null);
        }

        System.getProperties().put("proxySet", "false");
        System.getProperties().put("proxyHost", "");
        System.getProperties().put("proxyPort", "");
        enableProxyAuthentication = false;
    }

    String proxyHost, proxyPort, proxyUser, proxyPassword;
    boolean enableProxyAuthentication = false;
}
