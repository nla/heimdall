package heimdall.crawler.cache.disk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Consumer;

import org.openqa.selenium.remote.http.Contents;
import org.openqa.selenium.remote.http.HttpMethod;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import heimdall.crawler.URIUtils;
import heimdall.crawler.cache.NotCachedException;
import heimdall.crawler.cache.ServerResponseCache;
import heimdall.crawler.http.HttpCall;

public class OnDiskHashServerResponseCache implements ServerResponseCache
{
    // File systems have limits to the length of filenames. Therefore a hashing mechanism is used for the names of files
    // that represent requests (urls and data).
    
    // This implementation uses a combined filename key of <MD5>_<SHA>[_<MD5>_<SHA>] where the initial MD5 and SHA are hashes of the
    // request URI, and the second set are hashes of the POST/PUT data (if applicable).
    // This results in a maximum character length of 195 characters, which should be supported by modern file systems.
    
    // Both MD5 and SHA are used in combination to reduce the chance of a hash collision to practically (almost) zero.
    // It is still theoretically possible to have a collision, even if the possibility is extremely small.
    // For this reason, a better approach should probably be formulated to achieve absolute robustness.
    
    private static final int BYTE_BUFFER_SIZE = 16384;
    
    private String cachePath;
    private ThreadLocal<MessageDigest> md5ThreadLocal;
    private ThreadLocal<MessageDigest> shaThreadLocal;
    private ThreadLocal<Gson> gson;
    
    public void initialise(Properties properties, Consumer<Exception> errorListener) throws Exception
    {
        if(!properties.containsKey("SERVER_RESPONSE_CACHE__CACHE_PATH"))
        {
            throw new Exception("Property SERVER_RESPONSE_CACHE__CACHE_PATH not defined");
        }
        
        this.cachePath = properties.getProperty("SERVER_RESPONSE_CACHE__CACHE_PATH");
        this.md5ThreadLocal = new ThreadLocal<MessageDigest>(){
            protected MessageDigest initialValue()
            {
                try
                {
                    return MessageDigest.getInstance("MD5");
                }
                catch(Exception e)
                {
                    errorListener.accept(e);
                    return null;
                }
            }
        };
        this.shaThreadLocal = new ThreadLocal<MessageDigest>(){
            protected MessageDigest initialValue()
            {
                try
                {
                    return MessageDigest.getInstance("SHA");
                }
                catch(Exception e)
                {
                    errorListener.accept(e);
                    return null;
                }
            }
        };
        this.gson = new ThreadLocal<Gson>(){
            protected Gson initialValue()
            {
                return new GsonBuilder().registerTypeHierarchyAdapter(byte[].class,
                        new ByteArrayToBase64TypeAdapter()).create();
            }
        };
        
        File cachePathDirectory = new File(cachePath);
        
        if(cachePathDirectory.exists() && Boolean.parseBoolean(properties.getProperty("SERVER_RESPONSE_CACHE__CLEAR_ON_INIT", "true")))
        {
            clear();
        }
        if(!cachePathDirectory.exists())
        {
            cachePathDirectory.mkdirs();
        }
    }

    private String getMD5(String input) throws Exception
    {
        MessageDigest digest = md5ThreadLocal.get();
        
        if(digest==null)
        {
            throw new Exception("Failed to create MD5 message digest. See previous exception.");
        }
        
        HexFormat hex = HexFormat.of();
        return hex.formatHex(digest.digest(input.getBytes()));
    }
    
    private String getSHA(String input) throws Exception
    {
        MessageDigest digest = shaThreadLocal.get();
        
        if(digest==null)
        {
            throw new Exception("Failed to create SHA message digest. See previous exception.");
        }
        
        HexFormat hex = HexFormat.of();
        return hex.formatHex(digest.digest(input.getBytes()));
    }
    
    private String getFilePath(HttpRequest request) throws Exception
    {
        String uri = URIUtils.generateUniquePageURI(request);
        String path = cachePath+"/"+getMD5(uri)+"_"+getSHA(uri);
        
        if(request.getMethod().equals(HttpMethod.POST) || request.getMethod().equals(HttpMethod.PUT))
        {
            try(InputStream is = request.getContent().get())
            {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                int nRead;
                byte[] data = new byte[BYTE_BUFFER_SIZE];

                while((nRead=is.read(data, 0, data.length))!=-1)
                {
                    buffer.write(data, 0, nRead);
                }
                
                String base = Base64.getEncoder().encodeToString(buffer.toByteArray());
                path = path+"_"+getMD5(base)+"_"+getSHA(base);
            }
        }
        return path;
    }

    public void save(HttpCall call) throws Exception
    {
        SerializedHttpResponse response = new SerializedHttpResponse(call.getResponse());
        response.processPreSave();
        
        try(PrintWriter writer = new PrintWriter(new FileWriter(new File(getFilePath(call.getRequest())))))
        {
            writer.println(gson.get().toJson(response));
            writer.flush();
        }
    }
    
    public HttpCall load(HttpRequest request) throws NotCachedException, Exception
    {
        File file = new File(getFilePath(request));
        
        if(file.exists())
        {
            try(FileReader reader = new FileReader(file))
            {
                SerializedHttpResponse response = gson.get().fromJson(reader, SerializedHttpResponse.class);
                response.processPostLoad();
                
                return new HttpCall(request, response.getResponse());
            }
        }
        else
        {
            throw new NotCachedException();
        }
    }
    
    public void close() throws Exception
    {
        clear();
    }
    
    private void clear() throws Exception
    {
        File file = new File(cachePath);
        
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(file.toPath()))
        {
            for(Path entry: stream)
            {
                entry.toFile().delete();
            }
        }
        
        file.delete();
    }
    
    private static class ByteArrayToBase64TypeAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]>
    {
        public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
        {
            return Base64.getDecoder().decode(json.getAsString());
        }

        public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context)
        {
            return new JsonPrimitive(Base64.getEncoder().encodeToString(src));
        }
    }
    
    private class SerializedHttpResponse
    {
        private HttpResponse response;
        private byte[] contentBytes;
        
        public SerializedHttpResponse(HttpResponse response) throws Exception
        {
            this.response = response;
        }
        
        private void processPreSave() throws Exception
        {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            int nRead;
            byte[] data = new byte[BYTE_BUFFER_SIZE];
            
            try(InputStream is = response.getContent().get())
            {
                while((nRead=is.read(data, 0, data.length))!=-1)
                {
                    buffer.write(data, 0, nRead);
                }
                
                contentBytes = buffer.toByteArray();
            }
        }
        
        private void processPostLoad() throws Exception
        {
            response.setContent(Contents.bytes(contentBytes));
        }

        public HttpResponse getResponse()
        {
            return response;
        }
    }
}