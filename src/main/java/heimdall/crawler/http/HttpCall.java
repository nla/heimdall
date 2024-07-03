package heimdall.crawler.http;

import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

public class HttpCall
{
    private HttpRequest request;
    private HttpResponse response;
    
    public HttpCall(HttpRequest request, HttpResponse response)
    {
        this.request = request;
        this.response = response;
    }

    public HttpRequest getRequest()
    {
        return request;
    }

    public HttpResponse getResponse()
    {
        return response;
    }
}