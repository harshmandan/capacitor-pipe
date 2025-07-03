import { NPE } from 'npe-capacitor';

window.extractYouTubeStreams = async () => {
    const videoUrl = document.getElementById("videoUrlInput").value || "https://www.youtube.com/watch?v=kJQP7kiw5Fk";
    const resultDiv = document.getElementById("result");
    
    resultDiv.innerHTML = "Extracting stream info...";
    
    try {
        const result = await NPE.extractStreamInfo({ videoUrl });
        
        if (result.success) {
            const streamInfo = result.streamInfo;
            let html = `
                <h3>${streamInfo.title}</h3>
                <p><strong>Uploader:</strong> ${streamInfo.uploader}</p>
                <p><strong>Duration:</strong> ${streamInfo.duration} seconds</p>
                <p><strong>Views:</strong> ${streamInfo.viewCount}</p>
                
                <h4>Video Streams (${streamInfo.videoStreams.length})</h4>
                <ul>
            `;
            
            streamInfo.videoStreams.forEach((stream, i) => {
                html += `<li>${stream.resolution} - ${stream.format} - <a href="${stream.url}" target="_blank">Play</a></li>`;
            });
            
            html += `</ul><h4>Video-Only Streams (${streamInfo.videoOnlyStreams.length})</h4><ul>`;
            
            streamInfo.videoOnlyStreams.forEach((stream, i) => {
                html += `<li>${stream.resolution} - ${stream.format} - <a href="${stream.url}" target="_blank">Play</a></li>`;
            });
            
            html += `</ul><h4>Audio Streams (${streamInfo.audioStreams.length})</h4><ul>`;
            
            streamInfo.audioStreams.forEach((stream, i) => {
                html += `<li>${stream.bitrate}kbps - ${stream.format} - <a href="${stream.url}" target="_blank">Play</a></li>`;
            });
            
            html += "</ul>";
            resultDiv.innerHTML = html;
        } else {
            resultDiv.innerHTML = `<p style="color: red;">Error: ${result.error}</p>`;
        }
    } catch (error) {
        resultDiv.innerHTML = `<p style="color: red;">Error: ${error.message}</p>`;
    }
};
