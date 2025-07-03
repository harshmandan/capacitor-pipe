package ink.harsh.plugins.npe;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.AudioStream;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import okhttp3.OkHttpClient;

public class NPE {
    
    private static final String TAG = "NPE";
    private OkHttpClient httpClient;
    private boolean isInitialized = false;
    
    public NPE() {
        initializeNewPipe();
    }
    
    private void initializeNewPipe() {
        try {
            // Initialize HTTP client with NewPipe's exact configuration
            httpClient = new OkHttpClient.Builder()
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            
            // Create downloader following NewPipe's exact approach
            Downloader downloader = new Downloader() {
                private final Map<String, String> mCookies = new HashMap<>();
                
                {
                    // Set YouTube restricted mode cookie like NewPipe does
                    mCookies.put("youtube.com", "PREF=f2=8000000");
                }
                
                @Override
                public Response execute(Request request) throws IOException {
                    Log.d(TAG, "Executing request: " + request.url());
                    
                    okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
                            .url(request.url());
                    
                    // Use NewPipe's exact User-Agent (Firefox 128.0)
                    String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
                    requestBuilder.addHeader("User-Agent", USER_AGENT);
                    
                    // Add cookies for YouTube domain
                    if (request.url().contains("youtube.com")) {
                        String cookies = mCookies.get("youtube.com");
                        if (cookies != null) {
                            requestBuilder.addHeader("Cookie", cookies);
                        }
                    }
                    
                    // Handle POST requests with body data
                    if (request.dataToSend() != null && request.dataToSend().length > 0) {
                        requestBuilder.post(okhttp3.RequestBody.create(
                            okhttp3.MediaType.parse("application/json; charset=utf-8"),
                            request.dataToSend()
                        ));
                    }
                    
                    // Add headers from request
                    for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                        String headerName = header.getKey();
                        // Don't override our User-Agent
                        if (!headerName.equalsIgnoreCase("User-Agent")) {
                            for (String value : header.getValue()) {
                                requestBuilder.addHeader(headerName, value);
                            }
                        }
                    }
                    
                    // Execute request
                    okhttp3.Response response = httpClient.newCall(requestBuilder.build()).execute();
                    
                    // Convert response headers
                    Map<String, List<String>> responseHeaders = new HashMap<>();
                    for (String name : response.headers().names()) {
                        List<String> values = response.headers(name);
                        responseHeaders.put(name, values);
                    }

                    String responseBody = response.body().string();
                    Log.d(TAG, "Response: " + response.code() + " for " + request.url());
                    
                    return new Response(response.code(), response.message(), responseHeaders, responseBody, request.url());
                }
            };
            
            // Initialize NewPipe
            NewPipe.init(downloader);
            isInitialized = true;
            Log.d(TAG, "NPE initialized successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error initializing NPE", e);
            isInitialized = false;
        }
    }
    
    public JSObject extractStreamInfo(String videoUrl) {
        JSObject result = new JSObject();
        
        if (!isInitialized) {
            result.put("success", false);
            result.put("error", "NPE not initialized");
            return result;
        }
        
        try {
            Log.d(TAG, "Extracting stream info for: " + videoUrl);
            
            // Extract stream info
            StreamInfo streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl);
            
            if (streamInfo.getStreamType() == StreamType.VIDEO_STREAM) {
                JSObject streamInfoObj = new JSObject();
                
                // Basic info
                streamInfoObj.put("title", streamInfo.getName());
                streamInfoObj.put("duration", streamInfo.getDuration());
                streamInfoObj.put("uploader", streamInfo.getUploaderName());
                streamInfoObj.put("viewCount", streamInfo.getViewCount());
                streamInfoObj.put("thumbnailUrl", streamInfo.getThumbnails().isEmpty() ? "" : streamInfo.getThumbnails().get(0).getUrl());
                
                // Video streams (combined video+audio)
                JSArray videoStreams = new JSArray();
                for (VideoStream videoStream : streamInfo.getVideoStreams()) {
                    JSObject stream = new JSObject();
                    stream.put("url", videoStream.getUrl());
                    stream.put("format", videoStream.getFormat().toString());
                    stream.put("resolution", videoStream.getResolution());
                    if (videoStream.getFps() > 0) {
                        stream.put("fps", videoStream.getFps());
                    }
                    videoStreams.put(stream);
                }
                streamInfoObj.put("videoStreams", videoStreams);
                
                // Video-only streams (need to be combined with audio)
                JSArray videoOnlyStreams = new JSArray();
                for (VideoStream videoOnlyStream : streamInfo.getVideoOnlyStreams()) {
                    JSObject stream = new JSObject();
                    stream.put("url", videoOnlyStream.getContent());
                    stream.put("format", videoOnlyStream.getFormat().toString());
                    stream.put("resolution", videoOnlyStream.getResolution());
                    if (videoOnlyStream.getFps() > 0) {
                        stream.put("fps", videoOnlyStream.getFps());
                    }
                    videoOnlyStreams.put(stream);
                }
                streamInfoObj.put("videoOnlyStreams", videoOnlyStreams);
                
                // Audio streams
                JSArray audioStreams = new JSArray();
                for (AudioStream audioStream : streamInfo.getAudioStreams()) {
                    JSObject stream = new JSObject();
                    stream.put("url", audioStream.getContent());
                    stream.put("format", audioStream.getFormat().toString());
                    stream.put("bitrate", audioStream.getAverageBitrate());
                    audioStreams.put(stream);
                }
                streamInfoObj.put("audioStreams", audioStreams);
                
                result.put("success", true);
                result.put("streamInfo", streamInfoObj);
                
                Log.i(TAG, "Successfully extracted stream info for: " + streamInfo.getName());
                
            } else {
                result.put("success", false);
                result.put("error", "Invalid stream type or no stream info found");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error extracting stream info", e);
            result.put("success", false);
            result.put("error", "Failed to extract stream info: " + e.getMessage());
        }
        
        return result;
    }
}
