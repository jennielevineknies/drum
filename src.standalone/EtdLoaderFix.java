/*
 * Copyright (c) 2006 The University of Maryland. All Rights Reserved.
 * 
 */


import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Properties;
import java.util.Vector;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.Set;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.text.SimpleDateFormat;

// SQL
import java.sql.PreparedStatement;
import java.sql.ResultSet;

// IO
import java.io.*;

// XML
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.ElementHandler;
import org.dom4j.ElementPath;
import org.dom4j.InvalidXPathException;
import org.dom4j.Namespace;
import org.dom4j.Node;
import org.dom4j.QName;
import org.dom4j.Text;
import org.dom4j.XPath;

import org.dom4j.io.SAXReader;
import org.dom4j.io.DocumentSource;
import org.dom4j.io.DocumentResult;
import org.dom4j.io.DocumentInputSource;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;

import org.xml.sax.InputSource;

// XSL
import javax.xml.transform.dom.DOMSource;  

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;

import javax.xml.transform.stream.StreamSource; 
import javax.xml.transform.stream.StreamResult; 

// XPath
import org.jaxen.Function;
import org.jaxen.FunctionCallException;
import org.jaxen.FunctionContext;
import org.jaxen.Navigator;
import org.jaxen.SimpleFunctionContext;
import org.jaxen.XPathFunctionContext;

// Log4J
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

// DSpace
import org.dspace.core.Context;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Email;

import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.Collection;
import org.dspace.content.DCValue;
import org.dspace.content.FormatIdentifier;
import org.dspace.content.InstallItem;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;

import org.dspace.eperson.EPerson;
import org.dspace.eperson.Group;

import org.dspace.authorize.AuthorizeManager;
import org.dspace.authorize.ResourcePolicy;

import org.dspace.handle.HandleManager;

import org.dspace.browse.Browse;

// Marc4J
import org.marc4j.MarcXmlReader;
import org.marc4j.MarcStreamWriter;

import org.marc4j.marc.Record;

// Lims
import edu.umd.lims.util.ErrorHandling;


public class EtdLoaderFix {

  private static Logger log = Logger.getLogger(EtdLoaderFix.class);

  static long lRead = 0;
  static long lWritten = 0;
  static long lEmbargo = 0;

  static SAXReader reader = new SAXReader();
  static Transformer tDC = null;
  static Transformer tCollections = null;
  static Transformer tMeta2Marc = null;

  static Map namespace = new HashMap();
  static Map mXPath = new HashMap();
  
  static DocumentFactory df = DocumentFactory.getInstance();

  static Collection etdcollection = null;
  static EPerson etdeperson = null;

  static SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd");

  static MarcStreamWriter marcwriter = null;

  static Map mAllCollections = null;

  static Map mItems = null;

  /***************************************************************** main */
  /**
   * Command line interface.
   */

  public static void main(String args[]) throws Exception
  {

    try {

      // Properties
      Properties props     = System.getProperties();
      String strZipFile    = props.getProperty("etdloader.zipfile", null);
      String strSingleItem = props.getProperty("etdloader.singleitem", null);
      String strMarcFile   = props.getProperty("etdloader.marcfile", null);

      // dspace dir
      String strDspace     = ConfigurationManager.getProperty("dspace.dir");
      String strEPerson    = ConfigurationManager.getProperty("etdloader.eperson");
      String strCollection = ConfigurationManager.getProperty("etdloader.collection");

      // logging (log4j.defaultInitOverride needs to be set or
      // config/log4j.properties will be read and used additionally)
      PropertyConfigurator.configure(strDspace + "/config/log4j-etdloader.properties");

      // the transformers
      TransformerFactory tFactory = TransformerFactory.newInstance();
      tDC = tFactory.newTransformer(new StreamSource(new File(strDspace + "/load/etd2dc.xsl")));        
      tCollections = tFactory.newTransformer(new StreamSource(new File(strDspace + "/load/etd-collections.xsl")));        
      tMeta2Marc = tFactory.newTransformer(new StreamSource(new File(strDspace + "/load/etd2marc.xsl")));        

      // open the marc output file
      if (strMarcFile != null) {
	FileOutputStream fos = new FileOutputStream(new File(strMarcFile), true);
	marcwriter = new MarcStreamWriter(fos, "UTF-8");
      }

      // Get DSpace values
      Context context = new Context();
      context.setCurrentUser(etdeperson);
      context.setIgnoreAuthorization(true);

      if (strCollection == null) {
	throw new Exception("etdloader.collection not set");
      }
      etdcollection = Collection.find(context, Integer.parseInt(strCollection));
      if (etdcollection == null) {
	throw new Exception("Unable to find etdloader.collection: " + strCollection);
      }

      if (strEPerson == null) {
	throw new Exception("etdloader.eperson not set");
      }
      etdeperson = EPerson.findByEmail(context, strEPerson);
      if (etdeperson == null) {
	throw new Exception("Unable to find etdloader.eperson: " + strEPerson);
      }

      // Get the list of all collections
      mAllCollections = new HashMap();
      Collection c[] = Collection.findAll(context);
      for (int i=0; i < c.length; i++) {
	String strName = c[i].getMetadata("name");
	if (mAllCollections.containsKey(strName)) {
	  System.err.println("Error: duplicate collection names: " + strName);
	  System.exit(1);
	}
	
	mAllCollections.put(strName, c[i]);
      }

      BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
      mItems = new HashMap();
      String strLine = null;
      while ((strLine = br.readLine()) != null) {
	mItems.put(strLine.substring(0,4), strLine.substring(5));
      }

      // Open the zipfile
      ZipFile zip = new ZipFile(new File(strZipFile), ZipFile.OPEN_READ);

      // Get the list of entries
      Map map = readItems(zip);
      log.info("Found " + map.size() + " item(s)");

      // Process each entry
      for (Iterator i = map.keySet().iterator(); i.hasNext(); ) {
	String strItem = (String)i.next();

	lRead++;

	if (strSingleItem == null || strSingleItem.equals(strItem)) {
	  loadItem(zip, strItem, (List)map.get(strItem));
	}
      }

      context.complete();

    }

    catch (Exception e) {
      log.error("Uncaught exception: " + ErrorHandling.getStackTrace(e));
      System.exit(1);
    }

    finally {
      if (marcwriter != null) {
	try { marcwriter.close(); } catch (Exception e) {}
      }

      log.info("=====================================\n" +
	       "Records read:    " + lRead + "\n" +
	       "Records written: " + lWritten + "\n" +
	       "Embargoes:       " + lEmbargo);
    }

    System.exit(0);
  }


  /******************************************************** addBitstreams */
  /**
   * Add bitstreams to the item.
   */

  public static void addBitstreams(Context context, Item item, ZipFile zip, List files) throws Exception {

    // Get the ORIGINAL bundle which contains public bitstreams
    Bundle[] bundles = item.getBundles("ORIGINAL");
    Bundle bundle = null;

    if (bundles.length < 1) {
      bundle = item.createBundle("ORIGINAL");
    } else {
      bundle = bundles[0];
    }

    // Loop through the files
    for (int i = 2; i < files.size(); i += 2) {
      String strFileName = (String)files.get(i);
      ZipEntry ze = (ZipEntry)files.get(i+1);

      log.debug("Adding bitstream for " + strFileName);

      // Create the bitstream
      Bitstream bs = bundle.createBitstream(zip.getInputStream(ze));
      bs.setName(strFileName);

      // Set the format
      BitstreamFormat bf = FormatIdentifier.guessFormat(context, bs);
      bs.setFormat(bf);

      bs.update();
    }
  }


  /***************************************************************** addDC */
  /**
   * Add dubline core to the item
   */

  public static void addDC(Context context, Item item, Document meta) throws Exception {

    // Transform to dublin core
    DocumentSource source = new DocumentSource(meta);
    DocumentResult result = new DocumentResult();

    tDC.transform(source, result);

    Document dc = result.getDocument();

    if (log.isDebugEnabled()) {
      log.debug("dublin core:\n" + toString(dc));
    }

    // Loop through the elements
    List l = getXPath("/dublin_core/dcvalue").selectNodes(dc);
    for (Iterator i = l.iterator(); i.hasNext(); ) {
      Node ndc = (Node)i.next();

      String value = ndc.getText();

      String element = getXPath("@element").selectSingleNode(ndc).getText();

      Node n = getXPath("@qualifier").selectSingleNode(ndc);
      String qualifier = ((n == null || n.getText().equals("none")) ? null : n.getText());
      
      n = getXPath("@language").selectSingleNode(ndc);
      String language = ((n == null || n.getText().equals("none"))? null : n.getText());
      if (language == null) {
	language = ConfigurationManager.getProperty("default.language");
      }

      item.addDC(element, qualifier, language, value);
      log.debug(element + ":" + qualifier + ":" + language + ":" + value);
    }
  }


  /************************************************************ addEmbargo */
  /**
   * Add embargo to the bitstreams.
   */

  static Group etdgroup = null;
  static Group anongroup = null;

  public static void addEmbargo(Context context, Item item, String strEmbargo) throws Exception {

    log.debug("Adding embargo policies");

    // Get groups
    if (anongroup == null) {
      anongroup = Group.findByName(context, "Anonymous");
      if (anongroup == null) {
	throw new Exception("Unable to find Anonymous group");
      }
    }

    if (etdgroup == null) {
      etdgroup = Group.findByName(context, "ETD Embargo");
      if (etdgroup == null) {
	throw new Exception("Unable to find ETD Embargo group");
      }
    }
      
    // Setup the policies
    List lPolicies = new ArrayList();
    ResourcePolicy rp = null;

    if (strEmbargo.equals("never")) {
      log.info("Embargoed forever");
      rp = ResourcePolicy.create(context);
      rp.setAction(Constants.READ);
      rp.setGroup(etdgroup);
      lPolicies.add(rp);
    }
    else {
      Date date = format.parse(strEmbargo);
      log.info("Embargoed until " + date);

      rp = ResourcePolicy.create(context);
      rp.setAction(Constants.READ);
      rp.setGroup(etdgroup);
      rp.setEndDate(date);
      lPolicies.add(rp);

      rp = ResourcePolicy.create(context);
      rp.setAction(Constants.READ);
      rp.setGroup(anongroup);
      rp.setStartDate(date);
      lPolicies.add(rp);
    }

    // Loop through the bitstreams
    Bundle[] bundles = item.getBundles("ORIGINAL");
    Bundle bundle = bundles[0];

    Bitstream bs[] = bundle.getBitstreams();
    for (int i=0; i < bs.length; i++) {
      // Set the policies
      AuthorizeManager.removeAllPolicies(context, bs[i]);
      AuthorizeManager.addPolicies(context, lPolicies, bs[i]);
    }
  }


  /*********************************************************** checkTitle */
  /**
   * Check for duplicate titles.
   */

  private static void checkTitle(Context c, Item item, Set sCollections) throws Exception {

    String strSql = 
      "select" 
      + " count(distinct item_id)"
      + " from itemsbytitle"
      + " where sort_title=?"
      ;

    PreparedStatement st= c.getDBConnection().prepareStatement(strSql);

    // Get the list of collections
    StringBuffer sbCollections = new StringBuffer();
    sbCollections.append(etdcollection.getMetadata("name"));
    for (Iterator ic = sCollections.iterator(); ic.hasNext(); ) {
      Collection coll = (Collection)ic.next();
      sbCollections.append(", ");
      sbCollections.append(coll.getMetadata("name"));
    }

    // Get the title(s)
    DCValue dc[] = item.getDC("title", null, Item.ANY);

    // Process each title
    for (int i=0; i < dc.length; i++) {
      String title = Browse.getNormalizedTitle(dc[i].value, dc[i].language);
      log.debug("checking for duplicate title: " + title);
      
      st.clearParameters();
      st.setString(1, title);
      ResultSet rs = st.executeQuery();

      if (rs.next()) {
	int count = rs.getInt(1);

	if (count > 1) {
	  log.info("Duplicate title: " + title);
					  
	  // Get the email recipient
	  String email = ConfigurationManager.getProperty("mail.duplicate_title");
	  if (email == null) {
	    email = ConfigurationManager.getProperty("mail.admin");
	  }
		    
	  if (email != null) {
	    // Send the email
	    Email bean = ConfigurationManager.getEmail("duplicate_title");
	    bean.addRecipient(email);
	    bean.addArgument(title);
	    bean.addArgument(""+item.getID());
	    bean.addArgument(HandleManager.findHandle(c, item));
	    bean.addArgument(sbCollections.toString());
	    bean.send();
	  }
	}
      }
      rs.close();
    }
    st.close();
  }


  /*********************************************************** createMarc */
  /**
   * Create a marc record from the etd metadata.
   */

  public static void createMarc(Document meta, String strHandle, List files) throws Exception {

    if (marcwriter != null) {
      log.debug("Creating marc");

      // Convert etd metadata to marc xml
      DocumentSource source = new DocumentSource(meta);
      DocumentResult result = new DocumentResult();
        
      tMeta2Marc.setParameter("files", getFileTypes(files));
      tMeta2Marc.setParameter("handle", strHandle);
      tMeta2Marc.transform(source, result);

      // Convert marc xml to marc
      MarcXmlReader convert = new MarcXmlReader(new DocumentInputSource(result.getDocument()));
      Record record = convert.next();

      // Write out the marc record
      marcwriter.write(record);
    }
  }


  /******************************************************* getCollections */
  /**
   * Get additional mapped collections.
   */

  public static Set getCollections(Context context, Document meta) throws Exception {
    Set sCollections = new HashSet();

    DocumentSource source = new DocumentSource(meta);
    DocumentResult result = new DocumentResult();

    tCollections.transform(source, result);

    Document colls = result.getDocument();

    List l = getXPath("/collections/collection").selectNodes(colls);
    for (Iterator i = l.iterator(); i.hasNext(); ) {
      Node n = (Node)i.next();

      Collection coll = (Collection)mAllCollections.get(n.getText());
      if (coll == null) {
	log.error("Unable to lookup mapped collection: " + n.getText());
      } else {
	sCollections.add(coll);
      }
    }
    
    return sCollections;
  }


  /*********************************************************** getEmbargo */
  /**
   * Get embargo information.
   */

  public static String getEmbargo(Document meta) {
    String strEmbargo = null;

    Node n = getXPath("/DISS_submission/DISS_restriction/DISS_sales_restriction[@code=\"1\"]").selectSingleNode(meta);

    if (n != null) {
      Node n2 = getXPath("@remove").selectSingleNode(n);
      if (n2 != null) {
	strEmbargo = n2.getText();
      } else {
	strEmbargo = "never";
      }
    }

    if (strEmbargo != null) {
      log.debug("Item is embargoed; remove restrictions " + strEmbargo);
    }

    return strEmbargo;
  }


  /********************************************************** getFileTypes */
  /**
   */

  public static String getFileTypes(List files) throws IOException {

    HashSet h = new HashSet();

    // Loop through the files, extracting extensions
    for (int i = 2; i < files.size(); i += 2) {
      String strFileName = (String)files.get(i);

      int n = strFileName.lastIndexOf('.');
      if (n > -1) {
	h.add(strFileName.substring(n+1).trim().toLowerCase());
      }
    }

    if (h.contains("mp3")) {
      return "Text and audio.";
    } else if (h.contains("jpg")) {
      return "Text and images.";
    } else if (h.contains("xls")) {
      return "Text and spreadsheet.";
    } else if (h.contains("wav")) {
      return "Text and video.";
    } else {
      return "Text.";
    }
  }


/************************************************************* getXPath */
  /**
   * Get a compiled XPath object for the expression.  Cache.
   */

  private static XPath getXPath(String strXPath) throws InvalidXPathException {
    
    XPath xpath = null;

    if (mXPath.containsKey(strXPath)) {
        xpath = (XPath)mXPath.get(strXPath);

    } else {
        xpath = df.createXPath(strXPath);
        xpath.setNamespaceURIs(namespace);
        mXPath.put(strXPath, xpath);
    }

    return xpath;
  }


  /************************************************************* loadItem */
  /**
   * Load one item into DSpace.
   */

  public static void loadItem(ZipFile zip, String strItem, List files) {
    System.out.println("=====================================\n" +
		       "Fixing item " + strItem);

    Context context = null;

    try {
      // Setup the context
      context = new Context();
      context.setCurrentUser(etdeperson);
      context.setIgnoreAuthorization(true);

      // Read the ETD metadata
      ZipEntry ze = (ZipEntry)files.get(1);
      Document meta = reader.read(new InputSource(zip.getInputStream(ze)));
      if (log.isDebugEnabled()) {
	log.debug("ETD metadata:\n" + toString(meta));
      }

      // Map to additional collections
      Set sCollections = getCollections(context, meta);

      // Get the item
      if (!mItems.containsKey(strItem)) {
	  throw new Exception("missing handle: " + strItem);
      }
      String strHandle = (String)mItems.get(strItem);
      System.out.println("  handle: " + strHandle);
      Item item = (Item)HandleManager.resolveToObject(context, strHandle);
      String title = (item.getDC("title", null, Item.ANY))[0].value;
      System.out.println("  title: " + title);

      // Add mapped collections
      for (Iterator i = sCollections.iterator(); i.hasNext(); ) {
        Collection coll = (Collection)i.next();
	System.out.println("  collection: " + coll.getMetadata("name"));
	coll.addItem(item);
      }

      //context.abort();
      context.commit();
    }

    catch (Exception e) {
      log.error("Error loading item " + strItem + ": " +
		ErrorHandling.getStackTrace(e));
      if (context != null) {
	context.abort();
      }
    }

    finally {
      if (context != null) {
	try { context.complete(); } catch (Exception e) {}
      }
    }
  }


  /********************************************************** readItems */
  /**
   * Read and compile the entries from the zip file.  Return a map; the
   * key is the item number, the value is list of file name/ZipEntry pairs 
   * with the first entry being the metadata and the second entry being the
   * primary pdf.
   */

  public static Map readItems(ZipFile zip) {

    Map map = new TreeMap();

    log.info("Reading " + zip.size() + " zip file entries");

    // Loop through the entries
    for (Enumeration e = zip.entries(); e.hasMoreElements(); ) {
      ZipEntry ze = (ZipEntry)e.nextElement();
      String strName = ze.getName();

      log.debug("zip entry: " + strName);

      // skip directories
      if (ze.isDirectory()) {
	continue;
      }

      String s[] = strName.split("/");

      if (s.length >= 2) {
	String strItem = s[0];
	String strFileName = s[s.length - 1];

	// Get the list
	ArrayList lmap = null;
	if (map.containsKey(strItem)) {
	  lmap = (ArrayList)map.get(strItem);
	} else {
	  lmap = new ArrayList();
	  lmap.add(0, new Object());
	  lmap.add(1, new Object());
	  lmap.add(2, new Object());
	  lmap.add(3, new Object());
	  map.put(strItem, lmap);
	}

	// Put the file in the right position
	if (strFileName.equals("dissertation.xml") ||
	    strFileName.equals("umi-umd-" + strItem + ".xml"))
	{
	  lmap.set(0, strFileName);
	  lmap.set(1, ze);
	}
	else if (strFileName.equals("dissertation.pdf") ||
		 strFileName.equals("umi-umd-" + strItem + ".pdf"))
	{
	  lmap.set(2, strFileName);
	  lmap.set(3, ze);
	}
	else {
	  lmap.add(strFileName);
	  lmap.add(ze);
	}

      }
    }

    return map;
  }


  /**************************************************** reportCollections */
  /**
   * Report missing mapped collections
   */

  public static void reportCollections(Context context, Item item) throws Exception {
    // Get the title(s)
    DCValue dc[] = item.getDC("title", null, Item.ANY);
    String strTitle = dc[0].value;

    // Get the email recipient
    String email = ConfigurationManager.getProperty("load.alert.recipient");
    if (email == null) {
      email = ConfigurationManager.getProperty("mail.admin");
    }
		    
    if (email != null) {
      // Send the email
      Email bean = ConfigurationManager.getEmail("etd_collections");
      bean.addRecipient(email);
      bean.addArgument(strTitle);
      bean.addArgument(""+item.getID());
      bean.addArgument(HandleManager.findHandle(context, item));
      bean.addArgument(etdcollection.getMetadata("name"));
      bean.send();
    }
  }


  /*********************************************************** reportItem */
  /**
   * Report a successfully loaded item
   */

  private static void reportItem(Context c, Item item, String strHandle, Set sCollections) throws Exception {

    StringBuffer sb = new StringBuffer();

    sb.append("Item loaded: " + strHandle + "\n");

    // Title
    DCValue dc[] = item.getDC("title", null, Item.ANY);
    sb.append("  Title: " + dc[0].value + "\n");

    // Collections
    sb.append("  Collection: " + etdcollection.getMetadata("name") + "\n");
    for (Iterator ic = sCollections.iterator(); ic.hasNext(); ) {
      Collection coll = (Collection)ic.next();
      sb.append("  Collection: " + coll.getMetadata("name") + "\n");
    }

    log.info(sb.toString());
  }


  /************************************************************** toString */
  /**
   * Get string representation of xml Document.
   */

  public static String toString(Document doc) throws java.io.IOException {
    StringWriter sw = new StringWriter();
    OutputFormat format = OutputFormat.createPrettyPrint();
    XMLWriter writer = new XMLWriter(sw, format);
    writer.write(doc);
    return sw.toString();
  }

}


