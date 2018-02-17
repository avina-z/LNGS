// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.

package lngs.util;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

class ProxyAuthenticator extends Authenticator {

    public ProxyAuthenticator(String user, String password) {
        this.user = user;
        this.password = password;
    }

    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(user, password.toCharArray());
    }

    private String user, password;

}
