package heimdall.crawler.cache;

import java.util.Properties;
import java.util.function.Consumer;
import org.openqa.selenium.remote.http.HttpRequest;
import heimdall.crawler.http.HttpCall;

public interface ServerResponseCache extends AutoCloseable
{
    public void initialise(Properties properties, Consumer<Exception> errorListener) throws Exception;
    public void save(HttpCall call) throws Exception;
    public HttpCall load(HttpRequest request) throws NotCachedException, Exception;
}