package ink.harsh.plugins.npe;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "NPE")
public class NPEPlugin extends Plugin {

    private NPE implementation = new NPE();

    @PluginMethod
    public void extractStreamInfo(PluginCall call) {
        String videoUrl = call.getString("videoUrl");
        
        if (videoUrl == null || videoUrl.isEmpty()) {
            call.reject("Video URL is required");
            return;
        }
        
        // Execute in background thread since network operations are involved
        new Thread(() -> {
            try {
                JSObject result = implementation.extractStreamInfo(videoUrl);
                call.resolve(result);
            } catch (Exception e) {
                JSObject errorResult = new JSObject();
                errorResult.put("success", false);
                errorResult.put("error", "Plugin error: " + e.getMessage());
                call.resolve(errorResult);
            }
        }).start();
    }
}
