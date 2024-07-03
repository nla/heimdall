package heimdall.crawler.recorder;

import java.util.Properties;
import heimdall.crawler.http.HttpCall;

public interface CallRecorder
{
    public void initialise(Properties properties) throws Exception;
    public void dispose() throws Exception;
    
    public void recordCall(HttpCall cell) throws Exception;
}