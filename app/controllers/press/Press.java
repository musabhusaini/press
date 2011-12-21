package controllers.press;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import play.Play;
import play.exceptions.UnexpectedException;
import play.mvc.Controller;
import play.mvc.Util;
import play.templates.TemplateLoader;
import play.vfs.VirtualFile;
import press.CSSCompressor;
import press.CachingStrategy;
import press.JSCompressor;
import press.PluginConfig;
import press.io.CompressedFile;
import press.io.FileIO;

public class Press extends Controller {
	public static final DateTimeFormatter httpDateTimeFormatter = DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'");
	
    public static void getCompressedJS(String key) {
        key = FileIO.unescape(key);
        CompressedFile compressedFile = JSCompressor.getCompressedFile(key);
        renderCompressedFile(compressedFile, "JavaScript");
    }

    /**
     * Get the uncompressed, rendered js file (for dev mode)
     */
    public static void getRenderedJS(String file) {
    	serveRenderedFile(PluginConfig.js.srcDir, file, PluginConfig.js.compressedDir);
    }
    
    /**
     * Get the uncompressed, rendered css file (for dev mode)
     */
    public static void getRenderdCSS(String file) {
    	serveRenderedFile(PluginConfig.css.srcDir, file, PluginConfig.css.compressedDir);
    }
    
    /**
     * Serve a rendered file from a folder.
     * If press is disabled or the file is not found, this method always returns 404.
     * 
     * @param baseFolder The base folder which must contain the file
     * @param filePath The path to the file to render
     */
    @Util
    public static void serveRenderedFile(String baseFolder, String filePath, String cacheDir) {
    	if(PluginConfig.enabled) {
    		// we only serve rendered files this way if compression is disabled
    		notFound();
    	}
    	
    	VirtualFile folder = Play.getVirtualFile(baseFolder);
    	VirtualFile sourceFile = folder.child(filePath);
    	
    	// don't allow traversing out of the base folder
    	try {
	    	if(!sourceFile.getRealFile().getCanonicalPath().startsWith(folder.getRealFile().getCanonicalPath())) {
	    		notFound();
	    	}
    	} catch (IOException ioe) {
    		throw new UnexpectedException(ioe);
    	}
    	
    	renderText(TemplateLoader.load(sourceFile).render());
    }
    
    public static void getCompressedCSS(String key) {
        key = FileIO.unescape(key);
        CompressedFile compressedFile = CSSCompressor.getCompressedFile(key);
        renderCompressedFile(compressedFile, "CSS");
    }

    public static void getSingleCompressedJS(String key) {
        key = FileIO.unescape(key);
        CompressedFile compressedFile = JSCompressor.getSingleCompressedFile(key);
        renderCompressedFile(compressedFile, "JavaScript");
    }

    public static void getSingleCompressedCSS(String key) {
        key = FileIO.unescape(key);
        CompressedFile compressedFile = CSSCompressor.getSingleCompressedFile(key);
        renderCompressedFile(compressedFile, "CSS");
    }

    private static void renderCompressedFile(CompressedFile compressedFile, String type) {
        if (compressedFile == null) {
            renderBadResponse(type);
        }

        InputStream inputStream = compressedFile.inputStream();

        // This seems to be buggy, so instead of passing the file length we
        // reset the input stream and allow play to manually copy the bytes from
        // the input stream to the response
        // renderBinary(inputStream, compressedFile.name(),
        // compressedFile.length());

        try {
            if(inputStream.markSupported()) {
                inputStream.reset();
            }
        } catch (IOException e) {
            throw new UnexpectedException(e);
        }
        
        // If the caching strategy is always, the timestamp is not part of the key. If 
        // we let the browser cache, then the browser will keep holding old copies, even after
        // changing the files at the server and restarting the server, since the key will
        // stay the same.
        // If the caching strategy is never, we also don't want to cache at the browser, for 
        // obvious reasons.
        // If the caching strategy is Change, then the modified timestamp is a part of the key, 
        // so if the file changes, the key in the html file will be modified, and the browser will
        // request a new version. Each version can therefore be cached indefinitely.
        if(PluginConfig.cache.equals(CachingStrategy.Change)) {
        	response.setHeader("Cache-Control", "max-age=" + 31536000); // A year
        	response.setHeader("Expires", httpDateTimeFormatter.print(new DateTime().plusYears(1)));	
        	response.setHeader("Last-Modified", "Fri, 01 Jan 2010 00:00:00 GMT");
        	
        	if(request.headers.get("if-modified-since") != null) {
        		response.status = 304;
        		return;
        	}
        }
        
        renderBinary(inputStream, compressedFile.name());

    }

    public static void clearJSCache() {
        if (!PluginConfig.cacheClearEnabled) {
            forbidden();
        }

        int count = JSCompressor.clearCache();
        renderText("Cleared " + count + " JS files from cache");
    }

    public static void clearCSSCache() {
        if (!PluginConfig.cacheClearEnabled) {
            forbidden();
        }

        int count = CSSCompressor.clearCache();
        renderText("Cleared " + count + " CSS files from cache");
    }

    private static void renderBadResponse(String fileType) {
        String response = "/*\n";
        response += "The compressed " + fileType + " file could not be generated.\n";
        response += "This can occur in two situations:\n";
        response += "1. The time between when the page was rendered by the ";
        response += "server and when the browser requested the compressed ";
        response += "file was greater than the timeout. (The timeout is ";
        response += "currently configured to be ";
        response += PluginConfig.compressionKeyStorageTime + ")\n";
        response += "2. There was an exception thrown while rendering the ";
        response += "page.\n";
        response += "*/";
        renderBinaryResponse(response);
    }

    private static void renderBinaryResponse(String response) {
        try {
            renderBinary(new ByteArrayInputStream(response.getBytes("UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new UnexpectedException(e);
        }
    }
}