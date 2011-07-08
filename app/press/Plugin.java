package press;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import play.Play;
import play.PlayPlugin;
import play.mvc.Router;
import play.mvc.Router.ActionDefinition;
import press.io.FileIO;
import press.io.PressFileGlobber;

public class Plugin extends PlayPlugin {
    static ThreadLocal<JSCompressor> jsCompressor = new ThreadLocal<JSCompressor>();
    static ThreadLocal<CSSCompressor> cssCompressor = new ThreadLocal<CSSCompressor>();
    static ThreadLocal<Boolean> errorOccurred = new ThreadLocal<Boolean>();
    static ThreadLocal<Map<String, Boolean>> jsFiles = new ThreadLocal<Map<String, Boolean>>();
    static ThreadLocal<Map<String, Boolean>> cssFiles = new ThreadLocal<Map<String, Boolean>>();

    @Override
    public void onApplicationStart() {
        // Read the config each time the application is restarted
        PluginConfig.readConfig();

        // Clear the cache
        JSCompressor.clearCache();
        CSSCompressor.clearCache();
    }

    @Override
    public void beforeActionInvocation(Method actionMethod) {
        // Before each action, reinitialize variables
        jsCompressor.set(new JSCompressor());
        cssCompressor.set(new CSSCompressor());
        errorOccurred.set(false);
        jsFiles.set(new HashMap<String, Boolean>());
        cssFiles.set(new HashMap<String, Boolean>());
    }

    /**
     * Add a single JS file to compression
     */
    public static String addSingleJS(String fileName, boolean render) {
        checkJSFileExists(fileName);
        JSCompressor compressor = jsCompressor.get();
        String src = null;
        if (performCompression()) {
            String requestKey = compressor.compressedSingleFileUrl(fileName, render);
            if (PluginConfig.isInMemoryStorage()) {
                src = getSingleCompressedJSUrl(requestKey);
            } else {
                src  = getStaticUrl(requestKey);
            }
        } else {
            if(render) {
            	src = getRenderedJSUrl(fileName);
            } else {
            	src  = getStaticUrl(compressor.srcDir + fileName);
            }
        }

        return getScriptTag(src);
    }

    /**
     * Add a single CSS file to compression
     */
    public static String addSingleCSS(String fileName, boolean render) {
        checkCSSFileExists(fileName);
        CSSCompressor compressor = cssCompressor.get();
        String src = null;
        if (performCompression()) {
            String requestKey = compressor.compressedSingleFileUrl(fileName, render);
            if (PluginConfig.isInMemoryStorage()) {
                src = getSingleCompressedCSSUrl(requestKey);
            } else {
                src  = getStaticUrl(requestKey);
            }
        } else {
            if(render) {
            	src = getRenderedJSUrl(fileName);
            } else {
            	src  = getStaticUrl(compressor.srcDir + fileName);
            }
        }

        return getLinkTag(src);
    }

    /**
     * Adds the given source file(s) to the JS compressor, returning the file
     * signature to be output in HTML
     */
    public static String addJS(String src, boolean compress, boolean render) {
        JSCompressor compressor = jsCompressor.get();
        String baseUrl = compressor.srcDir;
        String result = "";
        for (String fileName : PressFileGlobber.getResolvedFiles(src, baseUrl)) {
            checkForJSDuplicates(fileName);

            if (performCompression()) {
                result += compressor.add(fileName, compress, render) + "\n";
            } else {
            	if (render) {
            		result += getScriptTag(getRenderedJSUrl(fileName));
            	} else {
            		result += getScriptTag(getStaticUrl(baseUrl + fileName));
            	}
            }
        }

        return result;
    }

    /**
     * Adds the given source file(s) to the CSS compressor, returning the file
     * signature to be output in HTML
     */
    public static String addCSS(String src, boolean compress, boolean render) {
        CSSCompressor compressor = cssCompressor.get();
        String baseUrl = compressor.srcDir;
        String result = "";
        for (String fileName : PressFileGlobber.getResolvedFiles(src, baseUrl)) {
            checkForCSSDuplicates(fileName);

            if (performCompression()) {
                result += compressor.add(fileName, compress, render) + "\n";
            } else {
            	if (render) {
            		result += getScriptTag(getRenderedCSSUrl(fileName));
            	} else {
            		result += getLinkTag(getStaticUrl(baseUrl + fileName));
            	}
            }
        }

        return result;
    }

    /**
     * Outputs the tag indicating where the compressed CSS should be included.
     */
    public static String compressedCSSTag() {
        if (performCompression()) {
            String requestKey = cssCompressor.get().closeRequest();
            return getLinkTag(getCompressedCSSUrl(requestKey));
        }
        return "";
    }

    /**
     * Outputs the tag indicating where the compressed CSS should be included.
     */
    public static String compressedJSTag() {
        if (performCompression()) {
            String requestKey = jsCompressor.get().closeRequest();
            return getScriptTag(getCompressedJSUrl(requestKey));
        }
        return "";
    }

    @Override
    public void afterActionInvocation() {
        // At the end of the action, save the list of files that will be
        // associated with this request
        if (jsCompressor.get() != null && cssCompressor.get() != null && performCompression()) {
            jsCompressor.get().saveFileList();
            cssCompressor.get().saveFileList();
        }
    }

    @Override
    public void onInvocationException(Throwable e) {
        errorOccurred.set(true);
    }

    /**
     * Indicates whether or not an error has occurred
     */
    public static boolean hasErrorOccurred() {
        return errorOccurred.get() == null || errorOccurred.get();
    }

    /**
     * Indicates whether or not to compress files
     */
    public static boolean performCompression() {
        return PluginConfig.enabled && !hasErrorOccurred();
    }

    /**
     * Check if the given JS file exists.
     */
    public static void checkJSFileExists(String fileName) {
        JSCompressor.checkJSFileExists(fileName);
    }

    /**
     * Check if the given CSS file exists.
     */
    public static void checkCSSFileExists(String fileName) {
        CSSCompressor.checkCSSFileExists(fileName);
    }

    /**
     * Check if the given JS file has already been included.
     */
    public static void checkForJSDuplicates(String fileName) {
        checkJSFileExists(fileName);
        checkForDuplicates(jsFiles.get(), fileName, JSCompressor.FILE_TYPE, JSCompressor.TAG_NAME);
    }

    /**
     * Check if the given CSS file has already been included.
     */
    public static void checkForCSSDuplicates(String fileName) {
        checkCSSFileExists(fileName);
        checkForDuplicates(cssFiles.get(), fileName, CSSCompressor.FILE_TYPE,
                CSSCompressor.TAG_NAME);
    }

    private static void checkForDuplicates(Map<String, Boolean> files, String fileName,
            String fileType, String tagName) {

        if (!files.containsKey(fileName)) {
            files.put(fileName, true);
            return;
        }

        throw new DuplicateFileException(fileType, fileName, tagName);
    }

    private static String getRenderedCSSUrl(String src) {
	    HashMap<String, Object> args = new HashMap<String, Object>();
	    args.put("file", src);
	    return getActionUrl("press.Press.getRenderedCSS", args);
	}

	private static String getRenderedJSUrl(String src) {
	    HashMap<String, Object> args = new HashMap<String, Object>();
	    args.put("file", src);
	    return getActionUrl("press.Press.getRenderedJS", args);
	}

	private static String getSingleCompressedCSSUrl(String requestKey) {
        return getCompressedUrl("press.Press.getSingleCompressedCSS", requestKey);
    }

    private static String getSingleCompressedJSUrl(String requestKey) {
        return getCompressedUrl("press.Press.getSingleCompressedJS", requestKey);
    }

    private static String getCompressedCSSUrl(String requestKey) {
        return getCompressedUrl("press.Press.getCompressedCSS", requestKey);
    }

    private static String getCompressedJSUrl(String requestKey) {
        return getCompressedUrl("press.Press.getCompressedJS", requestKey);
    }

    private static String getCompressedUrl(String action, String requestKey) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("key", FileIO.escape(requestKey));
        return getActionUrl(action, params);
    }

    /**
     * Returns a script tag which can be used to output uncompressed JavaScript
     * tags within the HTML.
     */
    private static String getScriptTag(String src) {
        return "<script src=\"" + src
                + "\" type=\"text/javascript\" language=\"javascript\" charset=\"utf-8\">"
                + "</script>\n";
    }

    /**
     * Returns a link tag which can be used to output uncompressed CSS tags
     * within the HTML.
     */
    private static String getLinkTag(String src) {
        return "<link href=\"" + src + "\" rel=\"stylesheet\" type=\"text/css\" charset=\"utf-8\">"
                + (press.PluginConfig.htmlCompatible ? "" : "</link>") + "\n";
    }
    
    private static String getActionUrl(String action, Map<String, Object> args) {
    	ActionDefinition def = Router.reverse(action, args);
    	def.absolute();
    	return def.url;
    }
    
    private static String getStaticUrl(String url) {
    	return Router.reverse(Play.getVirtualFile(url), true);    	
    }
    
}