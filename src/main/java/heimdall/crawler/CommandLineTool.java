package heimdall.crawler;

import java.io.FileReader;
import java.io.File;
import java.nio.file.Files;
import java.util.Properties;
import java.util.function.Consumer;
import heimdall.crawler.cache.ServerResponseCache;
import heimdall.crawler.cache.disk.OnDiskHashServerResponseCache;
import heimdall.crawler.recorder.warc.WARCCallRecorder;
import heimdall.crawler.recorder.CallRecorder;

public class CommandLineTool
{
    public static void main(String[] args) throws Exception
    {
        // Parse args
        
        if(args.length!=3)
        {
            System.out.println("Usage: <settings_file> <crawl_options_file> <seed_list_file>");
            System.exit(1);
            return;
        }
        
        String settingsPath = args[0];
        String crawlOptionsPath = args[1];
        String seedListPath = args[2];
        
        // Parse crawl options
        
        Properties crawlOptionProperties = new Properties();
        
        try(FileReader reader = new FileReader(crawlOptionsPath))
        {
            crawlOptionProperties.load(reader);
        }
        
        CrawlOptions crawlOptions = CrawlOptions.fromProperties(crawlOptionProperties);
        
        // Parse settings
        
        Properties settings = new Properties();
        
        try(FileReader reader = new FileReader(settingsPath))
        {
            settings.load(reader);
        }
        
        // Create call recorder
        
        CallRecorder callRecorder = (CallRecorder)Class.forName(settings.getProperty("CALL_RECORDER", WARCCallRecorder.class.getName())).getConstructor().newInstance();
        callRecorder.initialise(settings);
        
        // Create server response cache
        
        try(ServerResponseCache responseCache = (ServerResponseCache)Class.forName(settings.getProperty("SERVER_RESPONSE_CACHE", OnDiskHashServerResponseCache.class.getName())).getConstructor().newInstance())
        {
            responseCache.initialise(settings, new Consumer<Exception>(){
                public void accept(Exception e)
                {
                    e.printStackTrace();
                }
            });
            
            // Start crawl
            
            long referencePollingInterval = Long.parseLong(settings.getProperty("REFERENCE_POLLING_INTERVAL", "2000L"));
            
            CrawlerInstance instance = new CrawlerInstance(callRecorder, responseCache, crawlOptions, referencePollingInterval, new Consumer<Exception>(){
                public void accept(Exception e)
                {
                    e.printStackTrace();
                }
            });
            
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable(){
                public void run()
                {
                    instance.dispose();
                }
            }));
            
            instance.crawl(Files.readAllLines(new File(seedListPath).toPath()));
        }
    }
}