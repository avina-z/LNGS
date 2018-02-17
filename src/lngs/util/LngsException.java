// This source code is released under the GPL v3 license, http://www.gnu.org/licenses/gpl.html.
// This file is part of the LNGS project: http://sourceforge.net/projects/lngooglecalsync.

package lngs.util;

// It is poor design to throw base Exception objects so use this derived class.
public class LngsException extends Exception {
    public LngsException(String message) {
        super(message);
    }    
    
    public LngsException(String message, Throwable cause) {
        super(message, cause);
    }
}
