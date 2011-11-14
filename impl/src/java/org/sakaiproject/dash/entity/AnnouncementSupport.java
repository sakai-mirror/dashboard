/**
 * 
 */
package org.sakaiproject.dash.entity;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.announcement.api.AnnouncementMessage;
import org.sakaiproject.announcement.api.AnnouncementMessageHeader;
import org.sakaiproject.announcement.api.AnnouncementService;
import org.sakaiproject.announcement.api.AnnouncementChannel;
import org.sakaiproject.assignment.api.Assignment;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.dash.listener.EventProcessor;
import org.sakaiproject.dash.logic.DashboardLogic;
import org.sakaiproject.dash.logic.SakaiProxy;
import org.sakaiproject.dash.model.Context;
import org.sakaiproject.dash.model.NewsItem;
import org.sakaiproject.dash.model.SourceType;
import org.sakaiproject.entity.api.Entity;
import org.sakaiproject.entity.api.EntityPropertyNotDefinedException;
import org.sakaiproject.entity.api.EntityPropertyTypeException;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.entity.api.EntityManager;
import org.sakaiproject.event.api.Event;
import org.sakaiproject.message.api.Message;
import org.sakaiproject.thread_local.api.ThreadLocalManager;
import org.sakaiproject.time.api.Time;
import org.sakaiproject.user.api.User;
import org.sakaiproject.util.ResourceLoader;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.exception.PermissionException;

/**
 * THIS WILL BE MOVED TO THE ANNOUNCEMENT PROJECT IN SAKAI CORE ONCE THE INTERFACE IS MOVED TO KERNEL
 *
 */
public class AnnouncementSupport{
	
	private Log logger = LogFactory.getLog(AnnouncementSupport.class);
	
	protected SakaiProxy sakaiProxy;
	public void setSakaiProxy(SakaiProxy sakaiProxy) {
		this.sakaiProxy = sakaiProxy;
	}
	
	protected DashboardLogic dashboardLogic;
	public void setDashboardLogic(DashboardLogic dashboardLogic) {
		this.dashboardLogic = dashboardLogic;
	}

	protected AnnouncementService announcementService;
	public void setAnnouncementService(AnnouncementService announcementService) {
		this.announcementService = announcementService;
	}
	
	protected EntityManager entityManager;
	public void setEntityManager(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	protected ThreadLocalManager m_threadLocalManager = null;
	public void setThreadLocalManager(ThreadLocalManager service)
	{
		m_threadLocalManager = service;
	}

	public static final String IDENTIFIER = "announcement";
	
	public void init() {
		logger.info("init()");
		
		this.dashboardLogic.registerEntityType(new AnnouncementEntityType());
		this.dashboardLogic.registerEventProcessor(new AnnouncementNewEventProcessor());
		this.dashboardLogic.registerEventProcessor(new AnnouncementRemoveAnyEventProcessor());
		this.dashboardLogic.registerEventProcessor(new AnnouncementRemoveOwnEventProcessor());
		this.dashboardLogic.registerEventProcessor(new AnnouncementUpdateTitleEventProcessor());
		this.dashboardLogic.registerEventProcessor(new AnnouncementUpdateAccessEventProcessor());
		this.dashboardLogic.registerEventProcessor(new AnnouncementUpdateAvailabilityEventProcessor());
	}
	
	public Date getReleaseDate(Entity announcement) {
		Date releaseDate = null;
		if (announcement instanceof AnnouncementMessage)
		{
		ResourceProperties props = announcement.getProperties();
		Time releaseTime = null;
		try {
			releaseTime = props.getTimeProperty(SakaiProxy.ANNOUNCEMENT_RELEASE_DATE);
		} catch (EntityPropertyNotDefinedException e) {
			// do nothing -- no release date set, so return null
		} catch (EntityPropertyTypeException e) {
				logger.warn("Problem getting release date for announcement " + announcement.getReference(), e);
		}
		if(releaseTime != null) {
			releaseDate = new Date(releaseTime.getTime());
		}
		logger.debug("getReleaseDate() releaseDate: " + releaseDate);
		}
		return releaseDate;
	}

	public Date getRetractDate(Entity announcement) {
		Date retractDate = null;
		
		if (announcement instanceof AnnouncementMessage)
		{
		ResourceProperties props = announcement.getProperties();
		
		Time retractTime = null;
		try {
			retractTime = props.getTimeProperty(SakaiProxy.ANNOUNCEMENT_RETRACT_DATE);
		} catch (EntityPropertyNotDefinedException e) {
			// do nothing -- no retract date set, so return null
		} catch (EntityPropertyTypeException e) {
				logger.warn("Problem getting retract date for announcement " + announcement.getReference(), e);
		}
		if(retractTime != null) {
			retractDate = new Date(retractTime.getTime());
		}
		logger.debug("getRetractDate() retractDate: " + retractDate);
		}
		return retractDate;
	}
	
	private void createUpdateDashboardItemLinks(Event event, AnnouncementMessage annc) {
		
		String anncReference = annc.getReference();
		
		String anncTitle = annc.getAnnouncementHeader().getSubject();
		
		Context context = dashboardLogic.getContext(event.getContext());
		if(context == null) {
			context = dashboardLogic.createContext(event.getContext());
		}
		
		SourceType sourceType = dashboardLogic.getSourceType(IDENTIFIER);
		if(sourceType == null) {
			sourceType = dashboardLogic.createSourceType(IDENTIFIER, SakaiProxy.PERMIT_ANNOUNCEMENT_ACCESS, EntityLinkStrategy.SHOW_PROPERTIES);
		}
		
		NewsItem newsItem = dashboardLogic.getNewsItem(anncReference);
		if (newsItem == null)
		{
			// create NewsItem if not exist yet
			newsItem = dashboardLogic.createNewsItem(anncTitle, event.getEventTime(), null, anncReference, context, sourceType, null);
		}
		else
		{
			// remove all existing links
			dashboardLogic.removeNewsLinks(anncReference);
		}
		if(dashboardLogic.isAvailable(newsItem.getEntityReference(), IDENTIFIER)) {
			
			// availabe now
			dashboardLogic.createNewsLinks(newsItem);
			Date retractDate = getRetractDate(annc);
			if(retractDate != null && retractDate.after(new Date())) {
				dashboardLogic.scheduleAvailabilityCheck(newsItem.getEntityReference(), IDENTIFIER, retractDate);
			}
		} else {
			// not available now
			Date releaseDate = getReleaseDate(annc);
			if(releaseDate != null && releaseDate.after(new Date())) {
				dashboardLogic.scheduleAvailabilityCheck(newsItem.getEntityReference(), IDENTIFIER, releaseDate);
			}
		}
	}
	
	/**
	 * Inner class: AnnouncementEntityType
	 * @author zqian
	 *
	 */
	public class AnnouncementEntityType implements EntityType {
		
		protected static final String LABEL_METADATA = "annc_metadata-label";

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.entity.EntityType#getIdentifier()
		 */
		public String getIdentifier() {
			return IDENTIFIER;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.entity.EntityType#getEntityLinkStrategy(java.lang.String)
		 */
		public EntityLinkStrategy getEntityLinkStrategy(String entityReference) {
			
			return EntityLinkStrategy.SHOW_PROPERTIES;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.entity.EntityType#getValues(java.lang.String, java.lang.String)
		 */
		public Map<String, Object> getValues(String entityReference,
				String localeCode) {
			Map<String, Object> values = new HashMap<String, Object>();
			AnnouncementMessage announcement = (AnnouncementMessage) sakaiProxy.getEntity(entityReference);
			ResourceLoader rl = new ResourceLoader("dash_entity");
			if(announcement != null) {
				AnnouncementMessageHeader header = announcement.getAnnouncementHeader();
				ResourceProperties props = announcement.getProperties();
				values.put(EntityType.VALUE_ENTITY_TYPE, IDENTIFIER);
				DateFormat df = DateFormat.getDateTimeInstance();
				values.put(VALUE_NEWS_TIME, df.format(new Date(header.getDate().getTime())));
				values.put(VALUE_DESCRIPTION, announcement.getBody());
				values.put(VALUE_TITLE, header.getSubject());
				User user = header.getFrom();
				if(user != null) {
					values.put(VALUE_USER_NAME, user.getDisplayName());
				}
				
				// more info
				List<Map<String,String>> infoList = new ArrayList<Map<String,String>>();
				Map<String,String> infoItem = new HashMap<String,String>();
				infoItem.put(VALUE_INFO_LINK_URL, announcement.getUrl());
				infoItem.put(VALUE_INFO_LINK_TITLE, rl.getString("announcement.info.link"));
				infoList.add(infoItem);
				values.put(VALUE_MORE_INFO, infoList);
				
				// "attachments": [ ... ]
				List<Reference> attachments = header.getAttachments();
				if(attachments != null && ! attachments.isEmpty()) {
					List<Map<String,String>> attList = new ArrayList<Map<String,String>>();
					for(Reference ref : attachments) {
						ContentResource resource = (ContentResource) ref.getEntity();
						Map<String, String> attInfo = new HashMap<String, String>();
						attInfo.put(VALUE_ATTACHMENT_TITLE, resource.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME));
						attInfo.put(VALUE_ATTACHMENT_URL, resource.getUrl());
						attInfo.put(VALUE_ATTACHMENT_MIMETYPE, resource.getContentType());
						attInfo.put(VALUE_ATTACHMENT_SIZE, Long.toString(resource.getContentLength()));
						attInfo.put(VALUE_ATTACHMENT_TARGET, sakaiProxy.getTargetForMimetype(resource.getContentType()));
						attList.add(attInfo );
					}
					values.put(VALUE_ATTACHMENTS, attList);
				}
			}
			
			return values;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.entity.EntityType#getProperties(java.lang.String, java.lang.String)
		 */
		public Map<String, String> getProperties(String entityReference,
				String localeCode) {
			ResourceLoader rl = new ResourceLoader("dash_entity");
			Map<String, String> props = new HashMap<String, String>();
			props.put(LABEL_METADATA, rl.getString("announcement.metadata"));
			//props.put(LABEL_ATTACHMENTS, rl.getString("announcement.attachments"));
			return props;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.entity.EntityType#getOrder(java.lang.String, java.lang.String)
		 */
		public List<List<String>> getOrder(String entityReference, String localeCode) {
			List<List<String>> order = new ArrayList<List<String>>();
			
			List<String> section0 = new ArrayList<String>();
			section0.add(VALUE_TITLE);
			order.add(section0);
			
			List<String> section1 = new ArrayList<String>();
			section1.add(LABEL_METADATA);
			order.add(section1);
			List<String> section2 = new ArrayList<String>();
			section2.add(VALUE_DESCRIPTION);
			order.add(section2);
			List<String> section3 = new ArrayList<String>();
			section3.add(VALUE_ATTACHMENTS);
			order.add(section3);
			List<String> section4 = new ArrayList<String>();
			section4.add(VALUE_MORE_INFO);
			order.add(section4);
			return order;
		}

		public void init() {
			logger.info("init()");
			dashboardLogic.registerEntityType(this);
		}

		public boolean isAvailable(String entityReference) {
			AnnouncementMessage announcement = (AnnouncementMessage) sakaiProxy.getEntity(entityReference);
			if(announcement != null) {
				if(announcement.getHeader().getDraft()) {
					return false;
				}
				
				Date releaseDate = getReleaseDate(announcement);
				logger.debug("isAvailable() releaseDate: " + releaseDate);
				if(releaseDate != null && releaseDate.after(new Date())) {
					return false;
				}
				
				Date retractDate = getRetractDate(announcement);
				logger.debug("isAvailable() retractDate: " + retractDate);
				if(retractDate != null && retractDate.before(new Date())) {
					return false;
				}
				return true;
			}
			return false;
		}
		
		/**
		 * Get the channel id from a message reference.
		 */
		private String getChannelIdFromReference(String messageReference)
		{
			// "crack" the reference (a.k.a dereference, i.e. make a Reference)
			// and get the event id and channel reference
			Reference ref = entityManager.newReference(messageReference);
			String channelId = announcementService.channelReference(ref.getContext(), ref.getContainer());
			return channelId;
		} // getChannelIdFromReference
		
		public boolean isUserPermitted(String sakaiUserId, String accessPermission,
				String entityReference, String contextId) {
			boolean rv = false;
			
			String channelId = getChannelIdFromReference(entityReference);
			if (announcementService.allowGetChannel(channelId))
			{
				try
				{
					AnnouncementChannel c = announcementService.getAnnouncementChannel(channelId);
					List<Message> messages = c.getMessages(null, true);
					if (messages != null)
					{
						for (Message m : messages)
						{
							if (m.getReference().equals(entityReference))
							{
								rv = true;
								break;
							}
						}
					}
				}
				catch (IdUnusedException e)
				{
					logger.debug("isUserPermitted IdUnusedException: cannot find announcement channel id=" + channelId);
				}
				catch (PermissionException e)
				{
					logger.debug("isUserPermitted PermissionException: cannot get announcement channel id=" + channelId);
				}
			}
			
			return rv;
		}

		public String getString(String key, String dflt) {
			ResourceLoader rl = new ResourceLoader("dash_entity");
			return rl.getString(key, dflt);
		}

		public String getGroupTitle(int numberOfItems, String contextTitle) {
			ResourceLoader rl = new ResourceLoader("dash_entity");
			Object[] args = new Object[]{ numberOfItems, contextTitle };
			return rl.getFormattedMessage("announcement.grouped.title", args );
	}

		public String getIconUrl(String subtype) {
			// return the same image as used with Announcement tool
			return "/library/image/silk/flag_blue.png";
		}

		public String getAccessUrlLabel(String subtype) {
			ResourceLoader rl = new ResourceLoader("dash_entity");
			
			String key = "announcement.access.label";
			String label = rl.getString(key);
			
			return label;
		}
	}
	
	/**
	 * Inner class: AnnouncementNewEventProcessor
	 * @author zqian
	 *
	 */
	public class AnnouncementNewEventProcessor implements EventProcessor {
		
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {

			return SakaiProxy.EVENT_ANNOUNCEMENT_NEW;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement new: create news links and news item for " + event.getResource());
			}
			
			String eventId = event.getEvent();
			
			Entity entity = sakaiProxy.getEntity(event.getResource());
			if(entity != null && entity instanceof AnnouncementMessage) {
				AnnouncementMessage annc = (AnnouncementMessage) entity;
				createUpdateDashboardItemLinks(event, annc);
			
				} else {
				// for now, let's log the error
				logger.info(eventId + " is not processed for entityReference " + event.getResource());
			}
		}
	}

	/**
	 * Inner class: AnnouncementRemoveAnyEventProcessor
	 * @author zqian
	 *
	 */
	public class AnnouncementRemoveAnyEventProcessor implements EventProcessor {
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {

			return SakaiProxy.EVENT_ANNOUNCEMENT_REMOVE_ANY;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement remove any: removing news links and news item for " + event.getResource());
			}
			dashboardLogic.removeNewsItem(event.getResource());
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement remove any: removing calendar links and news item for " + event.getResource());
			}
			dashboardLogic.removeCalendarItems(event.getResource());
		}
	}
	
	/**
	 * Inner class: AnnouncementRemoveOwnEventProcessor
	 */
	public class AnnouncementRemoveOwnEventProcessor implements EventProcessor {
		
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {

			return SakaiProxy.EVENT_ANNOUNCEMENT_REMOVE_OWN;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement remove own: removing news links and news item for " + event.getResource());
			}
			dashboardLogic.removeNewsItem(event.getResource());
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement remove own: removing calendar links and news item for " + event.getResource());
			}
			dashboardLogic.removeCalendarItems(event.getResource());
		}
	}
	
	/**
	 * Inner Class: AnnouncementUpdateTitleEventProcessor
	 */
	public class AnnouncementUpdateTitleEventProcessor implements EventProcessor {
		
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {
			
			return SakaiProxy.EVENT_ANNC_UPDATE_TITLE;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links and calendar item for " + event.getResource());
			}
			// clean the threadlocal cache
			m_threadLocalManager.set(event.getResource(), null);
			Entity entity = sakaiProxy.getEntity(event.getResource());
			if(entity != null && entity instanceof AnnouncementMessage) {
				// get the assignment entity and its current title
				AnnouncementMessage annc = (AnnouncementMessage) entity;
				
				String title = annc.getAnnouncementHeader().getSubject();
				// update news item title
				dashboardLogic.reviseNewsItemTitle(annc.getReference(), title);
				
				// update calendar item title
				dashboardLogic.reviseCalendarItemsTitle(annc.getReference(), title);
			}
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement update title: update news links and news item for " + event.getResource());
			}

		}

	}
	
	/**
	 * Inner Class: AnnouncementUpdateAccessEventProcessor
	 */
	public class AnnouncementUpdateAccessEventProcessor implements EventProcessor {
		
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {
			
			return SakaiProxy.EVENT_ANNC_UPDATE_ACCESS;
		}

		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links and calendar item for " + event.getResource());
			}
			// clean the threadlocal cache
			m_threadLocalManager.set(event.getResource(), null);
			Entity entity = sakaiProxy.getEntity(event.getResource());
			
			if(entity != null && entity instanceof AnnouncementMessage) {
				// get the announcement entity
				AnnouncementMessage annc = (AnnouncementMessage) entity;
				String anncReference = annc.getReference();
				
				// update the calendar/news item links according to current announcement
				dashboardLogic.updateNewsLinks(anncReference);
				dashboardLogic.updateCalendarLinks(anncReference);
			}
			
			if(logger.isDebugEnabled()) {
				logger.debug("removing news links and news item for " + event.getResource());
			}

		}

	}
	/**
	 * Inner Class: AnnouncementUpdateAvailabilityEventProcessor
	 */
	public class AnnouncementUpdateAvailabilityEventProcessor implements EventProcessor {
		
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#getEventIdentifer()
		 */
		public String getEventIdentifer() {
			
				return SakaiProxy.EVENT_ANNC_UPDATE_AVAILABILITY;
		 }
			
		/* (non-Javadoc)
		 * @see org.sakaiproject.dash.listener.EventProcessor#processEvent(org.sakaiproject.event.api.Event)
		 */
		public void processEvent(Event event) {
			
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links and calendar item for " + event.getResource());
			}
			
			String entityReference = event.getResource();
			m_threadLocalManager.set(entityReference, null);
			Entity entity = sakaiProxy.getEntity(entityReference);
			if(entity != null && entity instanceof AnnouncementMessage) {
				AnnouncementMessage annc = (AnnouncementMessage) entity;
				boolean isDraft = annc.getHeader().getDraft();
				if (isDraft)
				{
					// if the announcement becomes draft, remove it from the dashboard tool
					dashboardLogic.removeNewsItem(entityReference);
				}
				else
				{
					createUpdateDashboardItemLinks(event, annc);	
				}
			}
			
			if(logger.isDebugEnabled()) {
				logger.debug("Announcement update availability: update  news links and news item for " + entityReference);
			}
		}
	}
}
