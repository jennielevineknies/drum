/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.webui.servlet;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.SQLException;

import org.apache.log4j.Logger;

import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.app.webui.util.JSPManager;
import org.dspace.app.webui.util.UIUtil;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.content.Collection;
import org.dspace.content.Item;
import org.dspace.content.WorkspaceItem;

/**
 * Class to deal with viewing workspace items during the authoring process.
 * Based heavily on the HandleServlet.
 *
 * @author Richard Jones
 * @version  $Revision: 5845 $
 */
public class ViewWorkspaceItemServlet 
    extends DSpaceServlet 
{

    /** log4j logger */
    private static Logger log = Logger.getLogger(ViewWorkspaceItemServlet.class);

    protected void doDSGet(Context c, 
        HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException, SQLException, AuthorizeException
    {
        // pass all requests to the same place for simplicty
        doDSPost(c, request, response);
    }
    
    protected void doDSPost(Context c, 
        HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException, SQLException, AuthorizeException
    {
        String button = UIUtil.getSubmitButton(request, "submit_error");
        
        if (button.equals("submit_view") 
            || button.equals("submit_full") 
            || button.equals("submit_simple"))
        {
            showMainPage(c, request, response);
        } else {
            showErrorPage(c, request, response);
        }

    }

   /**
     * show the workspace item home page
     *
     * @param context the context of the request
     * @param request the servlet request
     * @param response the servlet response
     */
    private void showMainPage(Context c, 
        HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException, SQLException, AuthorizeException
    {
        // get the value from the request
        int wsItemID = UIUtil.getIntParameter(request,"workspace_id");
        
        // get the workspace item, item and collections from the request value
        WorkspaceItem wsItem = WorkspaceItem.find(c, wsItemID);
        Item item = wsItem.getItem();
        //Collection[] collections = item.getCollections();
        Collection[] collections = {wsItem.getCollection()};
        
        // Ensure the user has authorisation
        AuthorizeManager.authorizeAction(c, item, Constants.READ);
        
        log.info(LogManager.getHeader(c, 
            "View Workspace Item Metadata", 
            "workspace_item_id="+wsItemID));
        
        // Full or simple display?
        boolean displayAll = false;
        String button = UIUtil.getSubmitButton(request, "submit_simple");
        if (button.equalsIgnoreCase("submit_full"))
        {
            displayAll = true;
        }
        
        // FIXME: we need to synchronise with the handle servlet to use the
        // display item JSP for both handled and un-handled items
        // Set attributes and display
        // request.setAttribute("wsItem", wsItem);
        request.setAttribute("display.all", Boolean.valueOf(displayAll));
        request.setAttribute("item", item);
        request.setAttribute("collections", collections);
        request.setAttribute("workspace_id", Integer.valueOf(wsItem.getID()));
        request.setAttribute("allbundles", "true");
        
        JSPManager.showJSP(request, response, "/display-item.jsp");
    }
    
   /**
     * Show error page if nothing has been <code>POST</code>ed to servlet
     *
     * @param context the context of the request
     * @param request the servlet request
     * @param response the servlet response
     */
    private void showErrorPage(Context context, 
        HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException, SQLException, AuthorizeException
    {
        int wsItemID = UIUtil.getIntParameter(request,"workspace_id");
        
        log.error(LogManager.getHeader(context, 
            "View Workspace Item Metadata Failed", 
            "workspace_item_id="+wsItemID));
        
        JSPManager.showJSP(request, response, "/workspace/wsv-error.jsp");
    }
}
