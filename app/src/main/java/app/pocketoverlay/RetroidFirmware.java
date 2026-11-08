package app.pocketoverlay;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.nio.charset.StandardCharsets;

/** Connection to the stock Retroid firmware service. */
final class RetroidFirmware {
    private IBinder service;
    private boolean checkedForService;

    String query(String command) {
        if (!checkedForService || (service != null && !service.isBinderAlive())) {
            service = findService();
            checkedForService = true;
        }
        if (service == null) {
            return "";
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeStringArray(new String[]{command, "1"});
            if (service.transact(0, request, reply, 0)) {
                byte[] bytes = reply.createByteArray();
                if (bytes != null) {
                    return new String(bytes, StandardCharsets.UTF_8).trim();
                }
            }
        } catch (RemoteException | RuntimeException ignored) {
            // Other firmware can hide the service or use a different protocol.
        } finally {
            request.recycle();
            reply.recycle();
        }
        return "";
    }

    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private static IBinder findService() {
        try {
            Class<?> services = Class.forName("android.os.ServiceManager");
            return (IBinder) services.getDeclaredMethod("getService", String.class)
                    .invoke(null, "PServerBinder");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
