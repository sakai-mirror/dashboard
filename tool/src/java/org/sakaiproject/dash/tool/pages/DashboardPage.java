package org.sakaiproject.dash.tool.pages;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import net.sf.json.JSONObject;

import org.apache.log4j.Logger;
import org.apache.wicket.IRequestTarget;
import org.apache.wicket.RequestCycle;
import org.apache.wicket.behavior.AbstractAjaxBehavior;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.protocol.http.WebRequest;
import org.apache.wicket.request.target.basic.StringRequestTarget;
import org.sakaiproject.dash.model.NewsItem;
import org.sakaiproject.dash.tool.panels.CalendarLinksPanel;
import org.sakaiproject.dash.tool.panels.NewsLinksPanel;
import org.sakaiproject.dash.tool.util.JsonHelper;

/**
 * 
 * 
 * 
 *
 */
public class DashboardPage extends BasePage {
	
	private static final Logger logger = Logger.getLogger(DashboardPage.class); 
	
	private static final String DATE_FORMAT = "dd-MMM-yyyy";
	private static final String TIME_FORMAT = "HH:mm";
	protected static final String DATETIME_FORMAT = "dd-MMM-yyyy HH:mm";
	
	protected int pageSize = 5;

	protected String selectedCalendarTab;
	protected String selectedNewsTab;
	
	public DashboardPage() {
		
		final WebMarkupContainer dashboardPage = new WebMarkupContainer("dashboard-page");
		dashboardPage.setOutputMarkupId(true);
		add(dashboardPage);
		
		CalendarLinksPanel calendarPanel = new CalendarLinksPanel("calendarPanel");
		calendarPanel.setOutputMarkupId(true);
		dashboardPage.add(calendarPanel);
		
		NewsLinksPanel newsPanel = new NewsLinksPanel("newsPanel");
		newsPanel.setOutputMarkupId(true);
		dashboardPage.add(newsPanel);
		
        AbstractAjaxBehavior entityDetailRequest = new AbstractAjaxBehavior() {

			public void onRequest() {
				
				logger.info("entityDetailRequest.onClick() ");
				
				//get parameters
                final RequestCycle requestCycle = RequestCycle.get();

                WebRequest wr=(WebRequest)requestCycle.getRequest();

                HttpServletRequest hsr = wr.getHttpServletRequest();
                
                String entityReference = null;
                String entityType = null;
                int itemCount = 0;
                try {
                   BufferedReader br = hsr.getReader();

                   String  jsonString = br.readLine();
                   if((jsonString == null) || jsonString.isEmpty()){
                       logger.error(" no json found for entityReference: " + entityReference);
                   }
                   else {
                	   if(logger.isDebugEnabled()) {
                		   logger.info(" json  is :"+ jsonString);
                	   }
                       JSONObject jsonObject = JSONObject.fromObject(jsonString);
                       
                       entityReference = jsonObject.optString("entityReference", "");
                       entityType = jsonObject.optString("entityType", "");
                       itemCount = jsonObject.optInt("itemCount", 1);

                   }
                   

                } catch (IOException ex) {
                    logger.error(ex);
                }

                Locale locale = hsr.getLocale();
 				if(entityReference != null && ! entityReference.trim().equals("") && entityType != null && ! entityType.trim().equals("")) {
 					if(itemCount > 1) {
 						int pageSize = 20;
 						int pageNumber = 0;
 						String sakaiUserId = sakaiProxy.getCurrentUserId();
						List<NewsItem> items = dashboardLogic.getNewsItemsByGroupId(sakaiUserId, entityReference, pageSize, pageNumber);
						JsonHelper jsonHelper = new JsonHelper(dashboardLogic);
						String jsonString = jsonHelper.getJsonArrayFromList(items).toString();
		                logger.debug("Returning JSON:\n" + jsonString);
		                IRequestTarget t = new StringRequestTarget("application/json", "UTF-8", jsonString);
		                getRequestCycle().setRequestTarget(t);
 					} else {
 					
		                Map<String,Object> entityMap = dashboardLogic.getEntityMapping(entityType, entityReference, locale);
		                
		                String jsonString = getJsonStringFromMap(entityMap);
		                logger.debug("Returning JSON:\n" + jsonString);
		                IRequestTarget t = new StringRequestTarget("application/json", "UTF-8", jsonString);
		                getRequestCycle().setRequestTarget(t);
	 				}
	 			}
			}
        };
        dashboardPage.add(entityDetailRequest);
        dashboardPage.add(new Label("callbackUrl", entityDetailRequest.getCallbackUrl().toString()));
	}
		
	// should this be in JsonHelper ??
	protected String getJsonStringFromMap(Map<String, Object> map) {
		JsonHelper jsonHelper = new JsonHelper(dashboardLogic);
		JSONObject json = jsonHelper.getJsonObjectFromMap(map);
		//logger.info("Returning json: " + json.toString(3));
		return json.toString();
	}

	/**
	 * @return the selectedCalendarTab
	 */
	public String getSelectedCalendarTab() {
		return selectedCalendarTab;
	}

	/**
	 * @return the selectedNewsTab
	 */
	public String getSelectedNewsTab() {
		return selectedNewsTab;
	}

	/**
	 * @param selectedCalendarTab the selectedCalendarTab to set
	 */
	public void setSelectedCalendarTab(String selectedCalendarTab) {
		this.selectedCalendarTab = selectedCalendarTab;
	}

	/**
	 * @param selectedNewsTab the selectedNewsTab to set
	 */
	public void setSelectedNewsTab(String selectedNewsTab) {
		this.selectedNewsTab = selectedNewsTab;
	}

}
