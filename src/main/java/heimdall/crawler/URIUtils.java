package heimdall.crawler;

import java.net.URI;

import org.openqa.selenium.remote.http.HttpRequest;

public class URIUtils
{
    public static final String generateUniquePageURI(HttpRequest request) throws Exception
    {
        return request.getMethod().toString()+"_"+stripURLofFragment(request.getUri());
    }
    
    public static final String stripURLofFragment(String url) throws Exception
    {
        URI uri = new URI(url);
        uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),uri.getQuery(), null);
        return uri.toString();
    }
}