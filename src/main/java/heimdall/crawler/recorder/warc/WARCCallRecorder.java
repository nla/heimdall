package heimdall.crawler.recorder.warc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.netpreserve.jwarc.HttpRequest;
import org.netpreserve.jwarc.HttpResponse;
import org.netpreserve.jwarc.MediaType;
import org.netpreserve.jwarc.WarcReader;
import org.netpreserve.jwarc.WarcRequest;
import org.netpreserve.jwarc.WarcResponse;
import org.netpreserve.jwarc.WarcWriter;
import org.netpreserve.jwarc.Warcinfo;
import org.netpreserve.jwarc.cdx.CdxWriter;
import org.openqa.selenium.remote.http.HttpMethod;
import heimdall.crawler.http.HttpCall;
import heimdall.crawler.recorder.CallRecorder;

public class WARCCallRecorder implements CallRecorder
{
    private String warcPath;
    private String cdxPath;
    private WarcWriter warcWriter;
    private ReentrantLock lock;
    
    public void initialise(Properties properties) throws Exception
    {
        if(!properties.containsKey("CALL_RECORDER__WARC_PATH"))
        {
            throw new Exception("Setting CALL_RECORDER__WARC_PATH not defined");
        }
        if(!properties.containsKey("CALL_RECORDER__CDX_PATH"))
        {
            throw new Exception("Setting CALL_RECORDER__CDX_PATH not defined");
        }
        
        this.warcPath = properties.getProperty("CALL_RECORDER__WARC_PATH");
        this.cdxPath = properties.getProperty("CALL_RECORDER__CDX_PATH");
        this.lock = new ReentrantLock();
        
        HashMap<String, List<String>> warcInfoFields = new HashMap<String, List<String>>();
        warcInfoFields.put("software", Arrays.asList("nla-heimdall"));
        
        warcWriter = new WarcWriter(new FileOutputStream(warcPath));
        warcWriter.write(new Warcinfo.Builder()
                .fields(warcInfoFields)
                .build());
    }
    
    public void dispose() throws Exception
    {
        lock.lock();
        
        try
        {
            warcWriter.close();
        }
        finally
        {
            lock.unlock();
        }
        
        try(WarcReader reader = new WarcReader(Paths.get(warcPath));
                CdxWriter writer = new CdxWriter(new FileWriter(cdxPath)))
        {
            writer.onWarning(new Consumer<String>(){
                public void accept(String warning)
                {
                    System.out.println("[WARNING] "+warning);
                }
            });
            
            writer.writeHeaderLine();
            writer.process(reader, new File(warcPath).getName());
        }
    }
    
    public void recordCall(HttpCall call) throws Exception
    {
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        
        HttpRequest.Builder req = new org.netpreserve.jwarc.HttpRequest.Builder(call.getRequest().getMethod().toString(), call.getRequest().getUri());
        String contentType = null;
        
        for(String name: call.getRequest().getHeaderNames())
        {
            if(name.equalsIgnoreCase("content-length"))
            {
                req.addHeader(name, call.getRequest().getHeader(name));
            }
            else
            {
                for(String header: call.getRequest().getHeaders(name))
                {
                    req.addHeader(name, header);
                    
                    if(name.equalsIgnoreCase("content-type"))
                    {
                        contentType = header;
                    }
                }
            }
        }
        
        if(call.getRequest().getMethod().equals(HttpMethod.POST) || call.getRequest().getMethod().equals(HttpMethod.PUT))
        {
            InputStream is = call.getRequest().getContent().get();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            int nRead;
            byte[] data = new byte[16384];

            while((nRead=is.read(data, 0, data.length))!=-1)
            {
                buffer.write(data, 0, nRead);
            }

            req.body(contentType==null?null:MediaType.parse(contentType), buffer.toByteArray());
        }
        
        HttpRequest httpRequest = req.build();

        HttpResponse.Builder res = new HttpResponse.Builder(
                call.getResponse().getStatus(), ""); // TODO do we need to provide a reason phrase?
        contentType = null;
        
        for(String name: call.getResponse().getHeaderNames())
        {
            for(String header: call.getResponse().getHeaders(name))
            {
                if(name.equalsIgnoreCase("content-length"))
                {
                    res.addHeader(name, call.getResponse().getHeader(name));
                }
                else
                {
                    res.addHeader(name, header);
                    
                    if(name.equalsIgnoreCase("content-type"))
                    {
                        contentType = header;
                    } 
                }
            }
        }
        
        InputStream is = call.getResponse().getContent().get();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[16384];

        while((nRead=is.read(data, 0, data.length))!=-1)
        {
            buffer.write(data, 0, nRead);
        }
        
        res.body(contentType==null?null:MediaType.parse(contentType), buffer.toByteArray());  
        
        org.netpreserve.jwarc.HttpResponse httpResponse = res.build();
        
        WarcRequest.Builder warcRequestBuilder = new WarcRequest.Builder(call.getRequest().getUri());
        warcRequestBuilder.date(now);
        warcRequestBuilder.body(httpRequest);
        WarcRequest warcRequest = warcRequestBuilder.build();
        
        WarcResponse.Builder warcResponseBuilder = new WarcResponse.Builder(call.getRequest().getUri());
        warcResponseBuilder.date(now);
        warcResponseBuilder.body(httpResponse);
        warcResponseBuilder.concurrentTo(warcRequest.id());
        
        lock.lock();
        
        try
        {
            warcWriter.write(warcRequest);
            warcWriter.write(warcResponseBuilder.build());
        }
        catch(Exception e)
        {
            System.err.println("Error writing call "+call.getRequest().getUri());
            throw e;
        }
        finally
        {
            lock.unlock();
        }
    }
}