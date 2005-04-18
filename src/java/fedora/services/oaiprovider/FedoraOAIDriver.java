package fedora.services.oaiprovider;

import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;

import org.jrdf.graph.Literal;
import org.jrdf.graph.URIReference;
import org.trippi.*;

import fedora.client.Downloader;

import proai.driver.impl.*;
import proai.driver.*;
import proai.error.*;

/**
 * Implementation of the OAIDriver interface for Fedora.
 *
 * @author Edwin Shin, cwilper@cs.cornell.edu
 */
public class FedoraOAIDriver implements OAIDriver {

    public static final String NS = "driver.fedora.";

    public static final String PROP_BASEURL          = NS + "baseURL";
    public static final String PROP_USER             = NS + "user";
    public static final String PROP_PASS             = NS + "pass";
    public static final String PROP_IDENTIFY         = NS + "identify";
    public static final String PROP_ITEMID           = NS + "itemID";
    public static final String PROP_SETSPEC          = NS + "setSpec";
    public static final String PROP_SETSPEC_NAME     = NS + "setSpec.name";
    public static final String PROP_SETSPEC_DISSTYPE = NS + "setSpec.dissType";
    public static final String PROP_QUERY_FACTORY    = NS + "queryFactory";
    public static final String PROP_FORMATS          = NS + "md.formats";
    public static final String PROP_FORMAT_START     = NS + "md.format.";
    public static final String PROP_FORMAT_PFX_END        = ".mdPrefix";
    public static final String PROP_FORMAT_LOC_END        = ".loc";
    public static final String PROP_FORMAT_URI_END        = ".uri";
    public static final String PROP_FORMAT_DISSTYPE_END   = ".dissType";

    private QueryFactory m_queryFactory;
    private URL m_identify;
    private String m_fedoraBaseURL;
    private String m_fedoraUser;
    private String m_fedoraPass;
    private String m_itemID;
    private String m_itemSetPath;
    private String m_setSpec;
    private String m_setSpecName;
    private String m_setSpecDissType;
    
    private Collection m_metadataFormats;

    private Downloader m_downloader;
    
    public FedoraOAIDriver() {
    }
    
    //////////////////////////////////////////////////////////////////////////
    ///////////////////// Methods from proai.driver.OAIDriver ////////////////
    //////////////////////////////////////////////////////////////////////////

    public void init(Properties props) throws RepositoryException {

        m_fedoraBaseURL   = getRequired(props, PROP_BASEURL);
        if (!m_fedoraBaseURL.endsWith("/")) m_fedoraBaseURL += "/";
        m_fedoraUser      = getRequired(props, PROP_USER); 
        m_fedoraPass      = getRequired(props, PROP_PASS); 
        m_itemID          = getRequired(props, PROP_ITEMID);
        m_setSpec         = getRequired(props, PROP_SETSPEC);
        m_setSpecName     = getRequired(props, PROP_SETSPEC_NAME);
        m_setSpecDissType = getRequired(props, PROP_SETSPEC_DISSTYPE);

        m_metadataFormats = getMetadataFormats(props);

        try {
            m_identify = new URL(getRequired(props, PROP_IDENTIFY));
        } catch (MalformedURLException e) {
            throw new RepositoryException(
                    "Identify property is not a valid URL: " 
                    + props.getProperty(PROP_IDENTIFY), e);
        }

        String className = getRequired(props, PROP_QUERY_FACTORY);
        try {
            Class queryFactoryClass = Class.forName(className);
            m_queryFactory = (QueryFactory) queryFactoryClass.newInstance();
            m_queryFactory.init(props);
        } catch (Exception e) {
            throw new RepositoryException("Unable to initialize " + className, e);
        }

        try {
            URL baseURL = new URL(m_fedoraBaseURL);
            String h = baseURL.getHost();
            int p = baseURL.getPort();
            if (p < 0) p = baseURL.getDefaultPort();
            m_downloader = new Downloader(h, p, m_fedoraUser, m_fedoraPass);
        } catch (Exception e) {
            throw new RepositoryException("Error parsing baseURL", e);
        }
    }

    public void write(PrintWriter out) throws RepositoryException {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("proai-fedora-identify", ".xml");
            tempFile.deleteOnExit();
            m_downloader.get(m_identify.toString(), 
                             new FileOutputStream(tempFile));
            writeStream(new FileInputStream(tempFile), 
                        out, 
                        m_identify.toString());
        } catch (IOException e) {
            throw new RepositoryException("Error getting identify.xml from " 
                    + m_identify.toString(), e);
        } finally {
            if (tempFile != null) tempFile.delete(); 
        }
    }

    public Date getLatestDate() throws RepositoryException {
        Map parms = m_queryFactory.latestRecordDateQuery();
        TupleIterator tuples = null;
        try {
            tuples = getTuples(parms);
            if (tuples.hasNext()) {
                Literal dateLiteral = (Literal) tuples.next().get("date");
                if (dateLiteral == null) {
                    throw new RepositoryException("A row was returned, but it did not contain a 'date' binding");
                }
                return parseDate(dateLiteral.getLexicalForm());
            } else {
                // no tuples... what to do?
                throw new RepositoryException("No rows returned from query");
            }
        } catch (Exception e) {
            throw new RepositoryException("Error querying for latest changed record date", e);
        } finally {
            if (tuples != null) try { tuples.close(); } catch (Exception e) { }
        }
    }

    public RemoteIterator listMetadataFormats() throws RepositoryException {
        return new RemoteIteratorImpl(m_metadataFormats.iterator());
    }

    public RemoteIterator listSetInfo() throws RepositoryException {
        Map parms = m_queryFactory.setInfoQuery();
        TupleIterator tuples = null;
        try {
            tuples = getTuples(parms);
            while (tuples.hasNext()) {
                Map tuple = tuples.next();
                Literal setSpecLiteral = (Literal) tuple.get("setSpec");
                if (setSpecLiteral == null) throw new RepositoryException("Unexpected: got null setSpec");
                String setSpec = setSpecLiteral.getLexicalForm();
                Literal setNameLiteral = (Literal) tuple.get("setName");
                if (setNameLiteral == null) throw new RepositoryException("Unexpected: got null setName");
                String setName = setNameLiteral.getLexicalForm();
                URIReference setDissReference = (URIReference) tuple.get("setDiss");
                if (setDissReference == null) {
                    System.out.println(setSpec + " -> " + setName);
                } else {
                    String setDiss = setDissReference.getURI().toString();
                    System.out.println(setSpec + " -> " + setName + " -> " + setDiss);
                }
            }
        } catch (Exception e) {
            throw new RepositoryException("Error querying for set information", e);
        } finally {
            if (tuples != null) try { tuples.close(); } catch (Exception e) { }
        }
        return null;
    }

    public RemoteIterator listRecords(Date from, 
                                      Date until, 
                                      String mdPrefix, 
                                      boolean withContent) throws RepositoryException {
        // TODO Auto-generated method stub
        return null;
    }

    public void close() throws RepositoryException {
        // TODO Auto-generated method stub
        
    }

    //////////////////////////////////////////////////////////////////////////
    ////////////////////////////// Helper Methods ////////////////////////////
    //////////////////////////////////////////////////////////////////////////

    private TupleIterator getTuples(Map params) throws RepositoryException {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("proai-fedora-queryresult", ".xml");
            tempFile.deleteOnExit();
            String url = getQueryURL(params);
            System.out.println(url);
            m_downloader.get(url, new FileOutputStream(tempFile));
            return TupleIterator.fromStream(new FileInputStream(tempFile), RDFFormat.SPARQL);
        } catch (Exception e) {
            if (tempFile != null) tempFile.delete();
            throw new RepositoryException("Error querying remote repository", e);
        }
    }

    private String getQueryURL(Map params) {
        params.put("type", "tuples");
        params.put("format", RDFFormat.SPARQL.getName());
        StringBuffer url = new StringBuffer();
        url.append(m_fedoraBaseURL);
        url.append("risearch?");
        Iterator iter = params.keySet().iterator();
        int n = 0;
        while (iter.hasNext()) {
            String name = (String) iter.next();
            if (n > 0) {
                url.append("&");
            }
            n++;
            url.append(name);
            url.append('=');
            try {
                url.append(URLEncoder.encode((String) params.get(name), "UTF-8"));
            } catch (UnsupportedEncodingException e) { // UTF-8 won't fail
            }
        }
        return url.toString();
    }

    /**
     * @param props
     */
    private Collection getMetadataFormats(Properties props) throws RepositoryException {
        String formats[], prefix, namespaceURI, schemaLocation, dissType;
        List list = new ArrayList();
        
        // step through formats, getting appropriate properties for each
        formats = getRequired(props, PROP_FORMATS).split(" ");
        for (int i = 0; i < formats.length; i++) {
            prefix = formats[i];
            namespaceURI   = getRequired(props, PROP_FORMAT_START + prefix + PROP_FORMAT_URI_END);
            schemaLocation = getRequired(props, PROP_FORMAT_START + prefix + PROP_FORMAT_LOC_END);
            dissType       = getRequired(props, PROP_FORMAT_START + prefix + PROP_FORMAT_DISSTYPE_END);
            String otherPrefix = props.getProperty(PROP_FORMAT_START + prefix + PROP_FORMAT_PFX_END);
            if (otherPrefix != null) prefix = otherPrefix;
            list.add(new FedoraMetadataFormat(prefix, 
                                              namespaceURI, 
                                              schemaLocation, 
                                              dissType));
        }
        return list;
    }
    
    protected static String getRequired(Properties props, String key) 
            throws RepositoryException {
        String val = props.getProperty(key);
        if (val == null) {
            throw new RepositoryException("Required property is not set: " + key);
        } else {
            return val;
        }
    }

    private void writeStream(InputStream in, 
                             PrintWriter out, 
                             String source) throws RepositoryException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in));
            String line = reader.readLine();
            while (line != null) {
                out.println(line);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new RepositoryException("Error reading " + source, e);
        } finally {
            if (reader != null) try { reader.close(); } catch (Exception e) { }
        }
    }

    private Date parseDate(String dateString) throws RepositoryException {
        DateFormat formatter = null;
        if (dateString.length() == 19) {
            formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        } else if (dateString.length() == 20) {
            formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        } else if (dateString.length() == 23) {
            formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
        } else if (dateString.length() == 24) {
            formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        }
        try {
            return formatter.parse(dateString);
        } catch (Exception e) {
            throw new RepositoryException("Could not parse date: " + dateString);
        }
    }

}
