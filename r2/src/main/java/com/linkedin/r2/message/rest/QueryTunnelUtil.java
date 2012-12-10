package com.linkedin.r2.message.rest;

import com.linkedin.data.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import javax.activation.DataSource;
import javax.mail.MessagingException;
import javax.mail.internet.ContentType;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;
import org.apache.commons.io.IOUtils;


/**
 * Encode and decode functions for tunnelling requests. Long queries can be passed by moving the query
 * param line into the body, and the request reformulated into a POST. The original method is specified
 * by X-HTTP-Method-Override.
 * Tunneled request bodies can be in one of two forms:
 *     1. x-www-form-urlencoded with query params stored in the body
 *     2. Content-Type of multipart/mixed with 2 sections
 *         The first section should be of type x-www-form-urlencoded and contain the query params
 *         The second should contain any body, along with it's associated content-type
 *
 *         Example: Call http://localhost?ids=1,2,3 with a JSON body
 *           curl -v  -X POST -H "X-HTTP-Method-Override: GET" -H "Content-Type: multipart/mixed, boundary=xyz"
 *                --data $'--xyz\r\nContent-Type: application/x-www-form-urlencoded\r\n\r\nids=1,2,3\r\n--xyz\r\n
 *                Content-Type: application/json\r\n\r\n{"foo":"bar"}\r\n--xyz--'
 *                http://localhost
 *
 *         Example: Call http://localhost?ids=1,2,3 with no body
 *           curl -v  -X POST -H "X-HTTP-Method-Override: GET" -H "Content-Type: application/x-www-form-urlencoded"
 *                --data $'ids=1,2,3' http://localhost
 */
public class QueryTunnelUtil
{
  private static final String HEADER_METHOD_OVERRIDE = "X-HTTP-Method-Override";
  private static final String HEADER_CONTENT_TYPE = "Content-Type";
  private static final String FORM_URL_ENCODED = "application/x-www-form-urlencoded";
  private static final String MULTIPART = "multipart/mixed";
  private static final String MIXED = "mixed";
  private static final String CONTENT_LENGTH = "Content-Length";
  private static final String UTF8 = "UTF-8";

  /**
   * class supports static methods only
   */
  private QueryTunnelUtil()
  {

  }

  /**
   * @param request   a RestRequest object to be encoded as a tunneled POST
   * @param threshold the size of the query params above which the request will be encoded
   *
   * @return an encoded RestRequest
   */
  public static RestRequest encode(final RestRequest request, int threshold)
      throws URISyntaxException, MessagingException, IOException
  {
    URI uri = request.getURI();

    // Check to see if we should tunnel this request by testing the length of the query
    String query = uri.getRawQuery();

    if (query == null || query.length() < threshold)
    {
      return request;
    }

    RestRequestBuilder requestBuilder = new RestRequestBuilder(request);

    // reconstruct URI without query
    uri = new URI(uri.getScheme(),
                  uri.getUserInfo(),
                  uri.getHost(),
                  uri.getPort(),
                  uri.getPath(),
                  null,
                  uri.getFragment());

    // If there's no existing body, just pass the request as x-www-form-urlencoded
    ByteString entity = request.getEntity();
    if (entity == null || entity.length() == 0)
    {
      requestBuilder.setHeader(HEADER_CONTENT_TYPE, FORM_URL_ENCODED);
      requestBuilder.setEntity(ByteString.copyString(query, Charset.forName(UTF8)));
    }
    else
    {
      // If we have a body, we must preserve it, so use multipart/mixed encoding

      MimeMultipart multi = createMultiPartEntity(entity, request.getHeader(HEADER_CONTENT_TYPE), query);
      requestBuilder.setHeader(HEADER_CONTENT_TYPE, multi.getContentType());
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      multi.writeTo(os);
      requestBuilder.setEntity(ByteString.copy(os.toByteArray()));
    }

    // Set the base uri, supply the original method in the override header, and change method to POST
    requestBuilder.setURI(uri);
    requestBuilder.setHeader(HEADER_METHOD_OVERRIDE, requestBuilder.getMethod());
    requestBuilder.setMethod(RestMethod.POST);

    return requestBuilder.build();
  }

  /**
   * Takes a Request object that has been encoded for tunnelling as a POST with an X-HTTP-Override-Method header and
   * creates a new request that represents the intended original request
   *
   * @param request the request to be decoded
   *
   * @return a decoded RestRequest
   */
  public static RestRequest decode(final RestRequest request)
      throws MessagingException, IOException, URISyntaxException
  {
    if (request.getHeader(HEADER_METHOD_OVERRIDE) == null)
    {
      // Not a tunnelled request, just pass thru
      return request;
    }

    String query = null;
    byte[] entity = new byte[0];

    // All encoded requests will have a content type
    ContentType contentType = new ContentType(request.getHeader(HEADER_CONTENT_TYPE));

    RestRequestBuilder requestBuilder = request.builder();

    // Get copy of headers and remove the override
    Map<String, String> h = new HashMap<String, String>(request.getHeaders());
    h.remove(HEADER_METHOD_OVERRIDE);

    // Simple case, just extract query params from entity, append to query, and clear entity
    if (contentType.getBaseType().equals(FORM_URL_ENCODED))
    {
      query = request.getEntity().toString();
      h.remove(HEADER_CONTENT_TYPE);
      h.remove(CONTENT_LENGTH);
    }
    else if (contentType.getBaseType().equals(MULTIPART))
    {
      // Clear these in case there is no body part
      h.remove(HEADER_CONTENT_TYPE);
      h.remove(CONTENT_LENGTH);

      MimeMultipart multi = new MimeMultipart(new DataSource()
      {
        @Override
        public InputStream getInputStream()
            throws IOException
        {
          return request.getEntity().asInputStream();
        }

        @Override
        public OutputStream getOutputStream()
            throws IOException
        {
          return null;
        }

        @Override
        public String getContentType()
        {
          return request.getHeader(HEADER_CONTENT_TYPE);
        }

        @Override
        public String getName()
        {
          return null;
        }
      });

      for (int i = 0; i < multi.getCount(); i++)
      {
        MimeBodyPart part = (MimeBodyPart) multi.getBodyPart(i);

        if (part.isMimeType(FORM_URL_ENCODED) && query == null)
        {
          // Assume the first segment we come to that is urlencoded is the tunneled query params
          query = IOUtils.toString((ByteArrayInputStream) part.getContent(), UTF8);
        }
        else if (entity.length <= 0)
        {
          // Assume any other content is the intended entity.
          // NOTE: This will silently ignore any additional body segments
          entity = IOUtils.toByteArray((ByteArrayInputStream) part.getContent());
          h.put(CONTENT_LENGTH, Integer.toString(entity.length));
          h.put(HEADER_CONTENT_TYPE, part.getContentType());
        }
      }
    }

    // Based on what we've found, modify the request, then build it.
    if (query != null && query.length() > 0)
    {
      requestBuilder.setURI(new URI(request.getURI().toString() + "?" + query));
    }
    requestBuilder.setEntity(entity);
    requestBuilder.setHeaders(h);
    requestBuilder.setMethod(request.getHeader(HEADER_METHOD_OVERRIDE));

    return requestBuilder.build();
  }

  /**
   * Helper function to create multi-part MIME
   *
   * @param entity         the body of a request
   * @param entityContentType content type of the body
   * @param query          a query part of a request
   *
   * @return a ByteString that represents a multi-part encoded entity that contains both
   */
  private static MimeMultipart createMultiPartEntity(ByteString entity, String entityContentType, String query)
      throws MessagingException
  {
    MimeMultipart multi = new MimeMultipart(MIXED);

    // Create current entity with the associated type
    MimeBodyPart dataPart = new MimeBodyPart();

    dataPart.setContent(entity.copyBytes(), entityContentType);
    dataPart.setHeader(HEADER_CONTENT_TYPE, entityContentType);

    // Encode query params as form-urlencoded
    MimeBodyPart argPart = new MimeBodyPart();
    argPart.setContent(query, FORM_URL_ENCODED);
    argPart.setHeader(HEADER_CONTENT_TYPE, FORM_URL_ENCODED);

    multi.addBodyPart(argPart);
    multi.addBodyPart(dataPart);
    return multi;
  }
}