package heimdall.crawler.recorder.logging;

import java.util.Properties;
import heimdall.crawler.http.HttpCall;
import heimdall.crawler.recorder.CallRecorder;

public class LoggingCallRecorder implements CallRecorder
{
    public void initialise(Properties properties) throws Exception{}
    public void dispose() throws Exception{}
    
    public void recordCall(HttpCall call) throws Exception
    {
        System.out.println(call.getRequest().getUri()+" <> "+call.getResponse().getStatus());
    }
}