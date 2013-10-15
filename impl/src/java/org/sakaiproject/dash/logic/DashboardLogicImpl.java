/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2011 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.osedu.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 **********************************************************************************/

package org.sakaiproject.dash.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import net.sf.ehcache.Cache;

import org.apache.log4j.Logger;
import org.sakaiproject.dash.app.DashboardConfig;
import org.sakaiproject.dash.app.SakaiProxy;
import org.sakaiproject.dash.dao.DashboardDao;
import org.sakaiproject.dash.entity.DashboardEntityInfo;
import org.sakaiproject.dash.entity.RepeatingEventGenerator;
import org.sakaiproject.dash.listener.EventProcessor;
import org.sakaiproject.dash.model.AvailabilityCheck;
import org.sakaiproject.dash.model.CalendarItem;
import org.sakaiproject.dash.model.CalendarLink;
import org.sakaiproject.dash.model.Context;
import org.sakaiproject.dash.model.NewsItem;
import org.sakaiproject.dash.model.NewsLink;
import org.sakaiproject.dash.model.Person;
import org.sakaiproject.dash.model.RepeatingCalendarItem;
import org.sakaiproject.dash.model.SourceType;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.user.api.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * 
 *
 */
public class DashboardLogicImpl implements DashboardLogic {

	public DashboardLogicImpl(PlatformTransactionManager transactionManager) {
	    Assert.notNull(transactionManager, "The 'transactionManager' argument must not be null.");
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	private static Logger logger = Logger.getLogger(DashboardUserLogicImpl.class);
	
	// time before rechecking a task assignment, in milliseconds (one hour)
	private static final long TASK_LOCK_EXPIRATION_PERIOD = 1000L * 60L * 60L * 12L;

	private static final long TASK_LOCK_NEGOTIATION_LIMIT_MILLISECONDS = 1000L * 60L * 3L;
	
	protected static final Set<String> TASKS = new HashSet<String>(Arrays.asList(new String[]{
			TaskLock.CHECK_AVAILABILITY_OF_HIDDEN_ITEMS,
			TaskLock.EXPIRE_AND_PURGE_OLD_DASHBOARD_ITEMS,
			TaskLock.UPDATE_REPEATING_EVENTS
	})); 

	protected Map<String,EventProcessor> eventProcessors = new HashMap<String,EventProcessor>();
	protected Map<String,DashboardEntityInfo> dashboardEntityInfoMap = new HashMap<String,DashboardEntityInfo>();
	
	protected Map<String,Date> taskLockNegotiationsDeadlines = new HashMap<String,Date>();
	protected Map<String,Date> taskLockExpirationTimes = new HashMap<String,Date>();
	protected Map<String,String> taskLockServerAssignments = new HashMap<String,String>();
	
	protected Date horizon = new Date();

	protected Date delay = null;
	

	
	/************************************************************************
	 * Constructor-assigned transaction template
	 ************************************************************************/
	
	// single TransactionTemplate shared amongst methods in this instance
	private final TransactionTemplate transactionTemplate;
		
	/************************************************************************
	 * Spring-injected classes
	 ************************************************************************/
	
	protected SakaiProxy sakaiProxy;
	public void setSakaiProxy(SakaiProxy proxy) {
		this.sakaiProxy = proxy;
	}
	
	protected DashboardDao dao;
	public void setDao(DashboardDao dao) {
		this.dao = dao;
	}
	
	protected DashboardConfig dashboardConfig;
	public void setDashboardConfig(DashboardConfig dashboardConfig) {
		this.dashboardConfig = dashboardConfig;
	}

	protected Cache cache;

	public void setCache(Cache cache) {
		this.cache = cache;
	}

	/************************************************************************
	 * init() and destroy()
	 ************************************************************************/

	public void init() {
		logger.info("init()");
		
		Integer weeksToHorizon = dashboardConfig.getConfigValue(DashboardConfig.PROP_WEEKS_TO_HORIZON, DEFAULT_WEEKS_TO_HORIZON);
		this.horizon = new Date(System.currentTimeMillis() + weeksToHorizon.longValue() * 7L * ONE_DAY);
	
	}
	
	public void destroy() {
		logger.info("destroy()");
	}
		
	/************************************************************************
	 * DashboardLogic methods
	 ************************************************************************/
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#addCalendarItemsForRepeatingCalendarItem(org.sakaiproject.dash.model.RepeatingCalendarItem, java.util.Date, java.util.Date)
	 */
	@Override
	public void addCalendarItemsForRepeatingCalendarItem(RepeatingCalendarItem repeatingEvent, Date beginDate, Date endDate) {
		if(beginDate.before(endDate)) {
			if(repeatingEvent == null || repeatingEvent.getSourceType() == null || repeatingEvent.getSourceType().getIdentifier() == null) {
				// TODO: handle error: null parameters?
				logger.warn("TODO: handle error: null parameters?");
			} else {
				DashboardEntityInfo dashboardEntityInfo = this.getDashboardEntityInfo(repeatingEvent.getSourceType().getIdentifier());
				if(dashboardEntityInfo == null) {
					// TODO: handle error: entityType cannot be null
					logger.warn("TODO: handle error: entityType cannot be null");
				} else if(dashboardEntityInfo instanceof RepeatingEventGenerator) {
					
					List<CalendarItem> oldDates = dao.getCalendarItems(repeatingEvent);
					Map<Date, CalendarItem> oldDatesMap = new HashMap<Date, CalendarItem>();
					for(CalendarItem cItem: oldDates) {
						if(cItem.getSequenceNumber() == null || cItem.getCalendarTime() == null) {
							logger.warn("addCalendarItemsForRepeatingCalendarItem() -- Deleting bogus CalendarItem and all links to it: " + cItem);
							final Long itemId = cItem.getId();
							this.transactionTemplate.execute(new TransactionCallback(){

								@Override
								public Object doInTransaction(TransactionStatus status) {
									dao.deleteCalendarLinks(itemId);
									dao.deleteCalendarItem(itemId);
									return true;
								}
								
							});

						} else {
							oldDatesMap.put(cItem.getCalendarTime(), cItem);
						}
					}
					Map<Integer, Date> newDates = ((RepeatingEventGenerator) dashboardEntityInfo).generateRepeatingEventDates(repeatingEvent.getEntityReference(), beginDate, endDate);
					if(newDates == null) {
						// ignore: there are no new dates to add at this time
					} else {
						for(Map.Entry<Integer, Date> entry : newDates.entrySet()) {
							try {
								if(oldDatesMap.containsKey(entry.getValue())) {
									verifyCalendarItem(oldDatesMap.get(entry.getValue()), repeatingEvent, entry.getKey(), entry.getValue());
								} else {
									// create an instance
									CalendarItem calendarItem = createCalendarItem(repeatingEvent.getTitle(), entry.getValue(), repeatingEvent.getCalendarTimeLabelKey(), 
											repeatingEvent.getEntityReference(), repeatingEvent.getContext(), repeatingEvent.getSourceType(), repeatingEvent.getSubtype(), repeatingEvent, entry.getKey());
									// dao.addCalendarItem(calendarItem);
									// calendarItem = dao.getCalendarItem(repeatingEvent.getEntityReference(), repeatingEvent.getCalendarTimeLabelKey(), entry.getKey());
									if(calendarItem == null) {
										// this could occur if we are trying to add an instance that has already been added
										StringBuilder buf = new StringBuilder();
										buf.append("Error trying to add calendar item for repeating event (");
										buf.append(repeatingEvent);
										buf.append(") for date (");
										buf.append(entry.getValue());
										buf.append(") and sequence number (");
										buf.append(entry.getKey());
										buf.append("). Calendar item is null for repeating event: ");
										buf.append(repeatingEvent);
										buf.append(" :: Sequence Number: ");
										buf.append(entry.getKey());
										buf.append(" Date: ");
										buf.append(entry.getValue());
										logger.warn(buf);
									} else {
										createCalendarLinks(calendarItem);
									}
								}
							} catch(Exception e) {
								// this could occur if we are trying to add an instance that has already been added
								StringBuilder buf = new StringBuilder();
								buf.append("Error trying to add calendar item for repeating event (");
								buf.append(repeatingEvent);
								buf.append(") for date (");
								buf.append(entry.getValue());
								buf.append(") and sequence number (");
								buf.append(entry.getKey());
								buf.append("). Exception: ");
								buf.append(e);
								buf.append(" Message: ");
								buf.append(e.getMessage());
								logger.warn(buf);
							}
						}
					}
				} else {
					// TODO: handle error: entityType must be a RepeatingEventGenerator
					logger.warn("TODO: handle error: entityType must be a RepeatingEventGenerator");
				}
			}
		} else {
			// TODO: handle error: invalid parameters? beginDate must be before endDate
			logger.warn("TODO: handle error: invalid parameters? beginDate must be before endDate");
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#addCalendarLinks(java.lang.String, java.lang.String)
	 */
	@Override
	public void addCalendarLinks(String sakaiUserId, String contextId) {
		if(logger.isDebugEnabled()) {
			logger.debug("addCalendarLinks(" + sakaiUserId + "," + contextId + ") ");
		}
		int count = 0;
		Person person = this.getOrCreatePerson(sakaiUserId);
		if(person == null) {
			logger.warn("Failed attempt to add calendar links for non-existent user: " + sakaiUserId);
		} else {
			// TODO: deal with expired items
			List<CalendarItem> items = dao.getCalendarItemsByContext(contextId);
			if(items == null || items.isEmpty()) {
				StringBuilder message = new StringBuilder();
				message.append("There is no calendar events in context (");
				message.append(contextId);
				message.append(") for new user (");
				message.append(sakaiUserId);
				message.append(")");
				logger.info(message.toString());
			} else {
				final List<CalendarLink> calendarLinks = new ArrayList<CalendarLink>();
				for(CalendarItem item: items) {
					SourceType sourceType = item.getSourceType();
					DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(sourceType.getIdentifier());
					if(dashboardEntityInfo != null && dashboardEntityInfo.isUserPermitted(sakaiUserId, item.getEntityReference(), item.getContext().getContextId())) {
						CalendarLink calendarLink = new CalendarLink(person, item, item.getContext(), false, false);
						calendarLinks.add(calendarLink);
					}
				}
				if(calendarLinks.size() > 0) {
					
					count = (Integer) this.transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							
							int count = dao.addCalendarLinks(calendarLinks);
							return count;
						}});
					
				}
			}
		}
		if(logger.isDebugEnabled()) {
			StringBuilder buf = new StringBuilder("addCalendarLinks(");
			buf.append(sakaiUserId);
			buf.append(",");
			buf.append(contextId );
			buf.append(") added ");
			buf.append(count);
			buf.append(" calendarLinks");
			logger.debug(buf);
		}

	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#addNewsLinks(java.lang.String, java.lang.String)
	 */
	@Override
	public void addNewsLinks(String sakaiUserId, String contextId) {
		if(logger.isDebugEnabled()) {
			logger.debug("addNewsLinks(" + sakaiUserId + "," + contextId + ") ");
		}
		int count = 0;
		Person person = this.getOrCreatePerson(sakaiUserId);
		if(person == null) {
			logger.warn("Attempting to add news links for non-existent user: " + sakaiUserId);
		} else {
			// TODO: deal with expired items
			List<NewsItem> items = dao.getNewsItemsByContext(contextId);
			if(items == null || items.isEmpty()) {
				StringBuilder message = new StringBuilder();
				message.append("There is no news events in context (");
				message.append(contextId);
				message.append(") for new user (");
				message.append(sakaiUserId);
				message.append(")");
				logger.info(message.toString());
			} else {
				final List<NewsLink> newsLinks = new ArrayList<NewsLink>();
				for(NewsItem item: items) {
					SourceType sourceType = item.getSourceType();
					DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(sourceType.getIdentifier());
					if(dashboardEntityInfo != null && dashboardEntityInfo.isUserPermitted(sakaiUserId, item.getEntityReference(), item.getContext().getContextId()) ) {
						NewsLink newsLink = new NewsLink(person, item, item.getContext(), false, false);
						newsLinks.add(newsLink);
					}
				}
				if(newsLinks.size() > 0) {
					
					count = (Integer) this.transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							int count = dao.addNewsLinks(newsLinks);
							return count;
						}});
				}
			}
		}
		if(logger.isDebugEnabled()) {
			StringBuilder buf = new StringBuilder("addNewsLinks(");
			buf.append(sakaiUserId);
			buf.append(",");
			buf.append(contextId );
			buf.append(") added ");
			buf.append(count);
			buf.append(" newsLinks");
			logger.debug(buf);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createCalendarItem(java.lang.String, java.util.Date, java.lang.String, java.lang.String, org.sakaiproject.dash.model.Context, org.sakaiproject.dash.model.SourceType, java.lang.String, org.sakaiproject.dash.model.RepeatingCalendarItem, java.lang.Integer)
	 */
	@Override
	public CalendarItem createCalendarItem(final String title, final Date calendarTime,
			final String calendarTimeLabelKey, final String entityReference, 
			final Context context, final SourceType sourceType, final String subtype, 
			final RepeatingCalendarItem repeatingCalendarItem, final Integer sequenceNumber) {

		return (CalendarItem) transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				CalendarItem calendarItem = new CalendarItem(title, calendarTime,
						calendarTimeLabelKey, entityReference, context, sourceType, 
						subtype, repeatingCalendarItem, sequenceNumber);
				
				CalendarItem rv = null;
				boolean success = dao.addCalendarItem(calendarItem);
				if(success) {
					rv = dao.getCalendarItem(entityReference, calendarTimeLabelKey, sequenceNumber);
				}
				return rv;
			}});
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createCalendarLinks(org.sakaiproject.dash.model.CalendarItem)
	 */
	@Override
	public void createCalendarLinks(CalendarItem calendarItem) {
		if(logger.isDebugEnabled()) {
			logger.debug("createCalendarLinks(" + calendarItem + ")");
		}
		int count = 0;
		if(calendarItem != null) {
			DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(calendarItem.getSourceType().getIdentifier());
			if(dashboardEntityInfo != null) {
				Set<String> usersWithLinks = dao.listUsersWithLinks(calendarItem);
				
				final List<CalendarLink> calendarLinks = new ArrayList<CalendarLink>();
				List<String> sakaiIds = dashboardEntityInfo.getUsersWithAccess(calendarItem.getEntityReference());
				for(String sakaiId : sakaiIds) {
					if(usersWithLinks.contains(sakaiId)) {
						// do nothing -- link already exists
					} else {
						Person person = getOrCreatePerson(sakaiId);
						if(person == null) {
							logger.warn("Error retrieving user " + sakaiId);
						} else {
							CalendarLink link = new CalendarLink(person, calendarItem, calendarItem.getContext(), false, false);
							calendarLinks.add(link);
						}
					}
				}
				if(calendarLinks.size() > 0) {
					count = (Integer) this.transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							
							return dao.addCalendarLinks(calendarLinks);
						}
					});
				}
			}
		}
		if(logger.isDebugEnabled()) {
			StringBuilder buf = new StringBuilder("createCalendarLinks(");
			buf.append(calendarItem.getId());
			buf.append(") added ");
			buf.append(count);
			buf.append(" calendarLinks");
			logger.debug(buf);
		}

	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createContext(java.lang.String)
	 */
	@Override
	public Context createContext(String contextId) { 
		Context context = null;
		if (contextId.equals(DashboardLogic.MOTD_CONTEXT))
		{
			// add an exception for MOTD announcement, where the context id is "!site"
			context = new Context(contextId, "MOTD", this.sakaiProxy.getConfigParam("serverUr", "")+ "/access/content/public/MOTD%20files/");
		}
		else
		{
			// get the site id, title and url
			Site site = this.sakaiProxy.getSite(contextId);
			if(site != null) {
				context = new Context(site.getId(), site.getTitle(), site.getUrl());
			}
		}
		if (context != null)
		{
			final Context context_copy = new Context(context);
			this.transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					
					return dao.addContext(context_copy);
				}
				
			});
		}
		context = dao.getContext(contextId);
		return context;
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createNewsItem(java.lang.String, java.util.Date, java.lang.String, java.lang.String, org.sakaiproject.dash.model.Context, org.sakaiproject.dash.model.SourceType, java.lang.String)
	 */
	@Override
	public NewsItem createNewsItem(final String title, final Date newsTime,
			final String labelKey, final String entityReference, final Context context, 
			final SourceType sourceType, final String subtype) {
		
		// transaction
		return (NewsItem) transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				NewsItem newsItem = new NewsItem(title, newsTime, 
						labelKey, entityReference, context, sourceType, subtype);
				
				dao.addNewsItem(newsItem);

				return dao.getNewsItem(entityReference) ;
			}});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createNewsLinks(org.sakaiproject.dash.model.NewsItem)
	 */
	@Override
	public void createNewsLinks(NewsItem newsItem) {
		if(logger.isDebugEnabled()) {
			logger.debug("createNewsLinks(" + newsItem + ")");
		}
		if(newsItem != null) {
			DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(newsItem.getSourceType().getIdentifier());
			List<String> sakaiIds = dashboardEntityInfo.getUsersWithAccess(newsItem.getEntityReference());
			if(sakaiIds != null && sakaiIds.size() > 0) {
				final List<NewsLink> newsLinks = new ArrayList<NewsLink>();
				for(String sakaiId : sakaiIds) {
					try {
						Person person = getOrCreatePerson(sakaiId);
						if(person != null) {
							NewsLink link = new NewsLink(person, newsItem, newsItem.getContext(), false, false);
							newsLinks.add(link);
						}
					} catch(Exception e) {
						logger.warn("Error trying to retrieve or add person " + sakaiId + " for news-item " + newsItem, e);
					}
				}
				if(newsLinks.size() > 0) {
					transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							
							return dao.addNewsLinks(newsLinks);
						}
						
					});
				}
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createRepeatingCalendarItem(java.lang.String, java.util.Date, java.util.Date, java.lang.String, java.lang.String, org.sakaiproject.dash.model.Context, org.sakaiproject.dash.model.SourceType, java.lang.String, int)
	 */
	@Override
	public RepeatingCalendarItem createRepeatingCalendarItem(String title, Date firstTime,
			Date lastTime, final String calendarTimeLabelKey, final String entityReference, Context context, 
			SourceType sourceType, String frequency, int count) {
		
		final RepeatingCalendarItem repeatingCalendarItem = new RepeatingCalendarItem(title, firstTime,
				lastTime, calendarTimeLabelKey, entityReference, null, context, sourceType, frequency, count);
		
		return (RepeatingCalendarItem) transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				dao.addRepeatingCalendarItem(repeatingCalendarItem);
				
				RepeatingCalendarItem rci = dao.getRepeatingCalendarItem(entityReference, calendarTimeLabelKey);
				
				addCalendarItemsForRepeatingCalendarItem(rci, new Date(), horizon);
				
				return rci;
			}
			
		});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#createSourceType(java.lang.String)
	 */
	@Override
	public SourceType createSourceType(final String identifier) {
		SourceType sourceType = dao.getSourceType(identifier);
		if(sourceType == null) {
			final SourceType newSourceType = new SourceType(identifier);
			sourceType = (SourceType) transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					dao.addSourceType(newSourceType);
					return dao.getSourceType(identifier);
				}
				
			});
		} 
		return sourceType;
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getCalendarItem(long)
	 */
	@Override
	public CalendarItem getCalendarItem(long id) {
		
		return dao.getCalendarItem(id);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getCalendarItem(java.lang.String, java.lang.String, java.lang.Integer)
	 */
	@Override
	public CalendarItem getCalendarItem(String entityReference, String calendarTimeLabelKey, Integer sequenceNumber) {
		
		return dao.getCalendarItem(entityReference, calendarTimeLabelKey, sequenceNumber);
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getCalendarLink(java.lang.Long)
	 */
	@Override
	public CalendarLink getCalendarLink(Long id) {
		return dao.getCalendarLink(id);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getContext(java.lang.String)
	 */
	@Override
	public Context getContext(String contextId) {
		Context rv = null;
		try {
			rv = dao.getContext(contextId);
			
		} catch(Exception e) {
			logger.debug("No context retrieved for contextId: " + contextId);
		}
		
		if (rv == null)
		{
			// create context
			rv = createContext(contextId);
		}
		return rv;
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getDashboardEntityInfo(java.lang.String)
	 */
	@Override
	public DashboardEntityInfo getDashboardEntityInfo(String Identifier) {
		return this.dashboardEntityInfoMap.get(Identifier);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getEventProcessor(java.lang.String)
	 */
	@Override
	public EventProcessor getEventProcessor(String eventIdentifier) {
		return this.eventProcessors.get(eventIdentifier);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getFutureSequnceNumbers(java.lang.String, java.lang.String, java.lang.Integer)
	 */
	@Override
	public SortedSet<Integer> getFutureSequnceNumbers(String entityReference,
			String calendarTimeLabelKey, Integer firstSequenceNumber) {
		return dao.getFutureSequenceNumbers(entityReference, calendarTimeLabelKey, firstSequenceNumber);
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getNewsItem(long)
	 */
	@Override
	public NewsItem getNewsItem(long id) {
		
		return dao.getNewsItem(id);
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getNewsItem(java.lang.String)
	 */
	@Override
	public NewsItem getNewsItem(String entityReference) {
		
		return dao.getNewsItem(entityReference);
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getRepeatingCalendarItem(java.lang.String, java.lang.String)
	 */
	@Override
	public RepeatingCalendarItem getRepeatingCalendarItem(String entityReference, String calendarTimeLabelKey) {
		return dao.getRepeatingCalendarItem(entityReference, calendarTimeLabelKey);
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getRepeatingEventHorizon()
	 */
	@Override
	public Date getRepeatingEventHorizon() {
		
		return new Date(this.horizon.getTime());
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#getSourceType(java.lang.String)
	 */
	@Override
	public SourceType getSourceType(String identifier) {
		SourceType  rv = null;
		try {
			rv = dao.getSourceType(identifier);
		} catch(Exception e) {
			logger.debug("No context retrieved for identifier: " + identifier);
		}
		
		if (rv == null)
		{
			// create SourceType
			rv = createSourceType(identifier);
		}
		
		return rv ;
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#isAvailable(java.lang.String, java.lang.String)
	 */
	@Override
	public boolean isAvailable(String entityReference, String entityTypeId) {
		// assume entity is unavailable unless entityType callback says otherwise
		boolean isAvailable = false;
		if(entityReference == null || entityTypeId == null) {
			logger.warn("isAvailable() invoked with null parameter: " + entityReference + " :: " + entityTypeId);
		} else {
			DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(entityTypeId);
			if(dashboardEntityInfo == null) {
				logger.warn("isAvailable() invalid entityTypeId: " + entityTypeId);
			} else {
				isAvailable = dashboardEntityInfo.isAvailable(entityReference);
			}
		}
		return isAvailable;
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#registerEntityType(org.sakaiproject.dash.entity.DashboardEntityInfo)
	 */
	@Override
	public void registerEntityType(DashboardEntityInfo dashboardEntityInfo) {
		if(dashboardEntityInfo != null && dashboardEntityInfo.getIdentifier() != null) {
			this.dashboardEntityInfoMap.put(dashboardEntityInfo.getIdentifier(), dashboardEntityInfo);
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#registerEventProcessor(org.sakaiproject.dash.listener.EventProcessor)
	 */
	@Override
	public void registerEventProcessor(EventProcessor eventProcessor) {
		
		if(eventProcessor != null && eventProcessor.getEventIdentifer() != null) {
			this.eventProcessors.put(eventProcessor.getEventIdentifer(), eventProcessor);
		}
		
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeAllScheduledAvailabilityChecks(java.lang.String)
	 */
	@Override
	public void removeAllScheduledAvailabilityChecks(final String entityReference) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				dao.deleteAvailabilityChecks(entityReference);
				return true;
			}
			
		});
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeCalendarItem(java.lang.String, java.lang.String, java.lang.Integer)
	 */
	@Override
	public void removeCalendarItem(String entityReference,
			String calendarTimeLabelKey, Integer sequenceNumber) {
		
		if(logger.isDebugEnabled()) {
			logger.debug("removing calendar links and calendar item for " + entityReference + " " + calendarTimeLabelKey + " " + sequenceNumber);
		}
		CalendarItem item = dao.getCalendarItem(entityReference, calendarTimeLabelKey, sequenceNumber);
		if(item != null ) {
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links and calendar item for item: " + item);
			}
			
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links for item: " + item);
			}
			dao.deleteCalendarLinks(item.getId());
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar item: " + item);
			}
			final Long itemId = item.getId();
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					
					return dao.deleteCalendarItem(itemId);
				}
				
			});
		}
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeCalendarItems(java.lang.String)
	 */
	@Override
	public void removeCalendarItems(String entityReference) {
		
		if(logger.isDebugEnabled()) {
			logger.debug("removing calendar links and calendar item for " + entityReference);
		}
		List<CalendarItem> items = dao.getCalendarItems(entityReference);
		if(items != null && items.size() > 0) {
			for(CalendarItem item : items) {
			if(logger.isDebugEnabled()) {
				logger.debug("removing calendar links and calendar item for item: " + item);
			}
			
				if(logger.isDebugEnabled()) {
					logger.debug("removing calendar links for item: " + item);
				}
				dao.deleteCalendarLinks(item.getId());
				if(logger.isDebugEnabled()) {
					logger.debug("removing calendar item: " + item);
				}
				final Long itemId = item.getId();
				transactionTemplate.execute(new TransactionCallback(){

					@Override
					public Object doInTransaction(TransactionStatus status) {
						
						return dao.deleteCalendarItem(itemId);
					}
					
				});
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeCalendarLinks(java.lang.String, java.lang.String)
	 */
	@Override
	public void removeCalendarLinks(String sakaiUserId, String contextName) {
		if(logger.isDebugEnabled()) {
			logger.debug("removeCalendarLinks(" + sakaiUserId + "," + contextName + ") ");
		}
		Person person = dao.getPersonBySakaiId(sakaiUserId);
		if(person != null) {
			Context context = dao.getContext(contextName);
			if(context != null) {
				final Long personId = person.getId();
				final Long contextId = context.getId();
				transactionTemplate.execute(new TransactionCallback(){

					@Override
					public Object doInTransaction(TransactionStatus status) {
						
						return dao.deleteCalendarLinks(personId, contextId);
					}
					
				});
			}
		}
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeCalendarLinks(java.lang.String, java.lang.String, int)
	 */
	@Override
	public void removeCalendarLinks(String entityReference,
			String calendarTimeLabelKey, int sequenceNumber) {
		
		final CalendarItem calendarItem = dao.getCalendarItem(entityReference, calendarTimeLabelKey, sequenceNumber);
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				dao.deleteCalendarLinks(calendarItem.getId());
				return true;
			}
			
		});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeCalendarLinks(java.lang.String, java.lang.String, int)
	 */
	@Override
	public void removeCalendarLinks(String entityReference) {
		
		final List<CalendarItem> items = dao.getCalendarItems(entityReference);
		if(items != null && items.size() > 0) {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					int count = 0;
					for(CalendarItem item : items) {
						dao.deleteCalendarLinks(item.getId());
						count++;
					}
					return count;
				}
				
			});

			
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeNewsItem(java.lang.String)
	 */
	@Override
	public void removeNewsItem(String entityReference) {

		if(logger.isDebugEnabled()) {
			logger.debug("removing news links and news item for " + entityReference);
		}
		NewsItem item = dao.getNewsItem(entityReference);
		if(logger.isDebugEnabled()) {
			logger.debug("removing news links and news item for item: " + item);
		}
		if(item != null) {
			if(logger.isDebugEnabled()) {
				logger.debug("removing news links for item: " + item);
			}
			final Long itemId = item.getId();
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					dao.deleteNewsLinks(itemId);
					dao.deleteNewsItem(itemId);
					return true;
				}
				
			});

			if(logger.isDebugEnabled()) {
				logger.debug("removing news item: " + item);
			}
		}
		
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeNewsLinks(java.lang.String)
	 */
	@Override
	public void removeNewsLinks(String entityReference) {
		
		NewsItem item = dao.getNewsItem(entityReference);
		if(item != null) {
			final Long itemId = item.getId();
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					
					return dao.deleteNewsLinks(itemId);
				}
				
			});
			
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeNewsLinks(java.lang.String, java.lang.String)
	 */
	@Override
	public void removeNewsLinks(String sakaiUserId, String contextName) {
		if(logger.isDebugEnabled()) {
			logger.debug("removeNewsLinks(" + sakaiUserId + "," + contextName + ") ");
		}
		Person person = dao.getPersonBySakaiId(sakaiUserId);
		if(person != null) {
			Context context = dao.getContext(contextName);
			if(context != null) {
				final Long personId = person.getId();
				final Long contextId = context.getId();
				transactionTemplate.execute(new TransactionCallback(){

					@Override
					public Object doInTransaction(TransactionStatus status) {
						
						return dao.deleteCalendarLinks(personId, contextId);
					}
					
				});
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseCalendarItemsLabelKey(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void reviseCalendarItemsLabelKey(final String entityReference, final String oldLabelKey, final String newLabelKey) {
		if(entityReference == null || oldLabelKey == null || newLabelKey == null) {
			return;
		}
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				
				return dao.updateCalendarItemsLabelKey(entityReference, oldLabelKey, newLabelKey);
			}
			
		});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseCalendarItemsTime(java.lang.String, java.util.Date)
	 */
	@Override
	public void reviseCalendarItemsTime(String entityReference, final Date newTime) {
		
		final List<CalendarItem> items = dao.getCalendarItems(entityReference);
		if(items != null) {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					int count = 0;
					for(CalendarItem item : items) {
						dao.updateCalendarItemTime(item.getId(), newTime);
						count++;
					}
					return count;
				}
			});
		}
				
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseCalendarItemsTitle(java.lang.String, java.lang.String)
	 */
	@Override
	public void reviseCalendarItemsTitle(String entityReference, final String newTitle) {
		
		final List<CalendarItem> items = dao.getCalendarItems(entityReference);
		if(items != null) {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					int count = 0;
					for(CalendarItem item : items) {
						dao.updateCalendarItemTitle(item.getId(), newTitle);
						count++;
					}
					return count;
				}
			});
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseCalendarItemTime(java.lang.String, java.lang.String, java.lang.Integer, java.util.Date)
	 */
	@Override
	public void reviseCalendarItemTime(final String entityReference,
			final String labelKey, final Integer sequenceNumber, final Date newDate) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				return dao.updateCalendarItemTime(entityReference, labelKey, sequenceNumber, newDate);
			}});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseNewsItemTime(java.lang.String, java.util.Date, java.lang.String)
	 */
	@Override
	public void reviseNewsItemTime(String entityReference, final Date newTime, final String newGroupingIdentifier) {
		final NewsItem item = dao.getNewsItem(entityReference);
		if(item == null) {
			logger.warn("Attempting to revise time of non-existent news item: " + entityReference);
		} else {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					
					return dao.updateNewsItemTime(item.getId(), newTime, newGroupingIdentifier);
				}
			});
			
		}
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseNewsItemTitle(java.lang.String, java.lang.String, java.util.Date, java.lang.String, java.lang.String)
	 */
	@Override
	public void reviseNewsItemTitle(String entityReference, final String newTitle, final Date newNewsTime, final String newLabelKey, final String newGroupingIdentifier) {
		
		final NewsItem item = dao.getNewsItem(entityReference);
		if(item == null) {
			logger.warn("Attempting to revise title of non-existent news item: " + entityReference);
		} else {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					
					return dao.updateNewsItemTitle(item.getId(), newTitle, newNewsTime, newLabelKey, newGroupingIdentifier);
				}
				
			});
			
		}
		
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseRepeatingCalendarItemFrequency(java.lang.String, java.lang.String)
	 */
	@Override
	public boolean reviseRepeatingCalendarItemFrequency(final String entityReference,
			final String frequency) {
		return (Boolean) transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				
				return dao.updateRepeatingCalendarItemFrequency(entityReference, frequency);
			}
			
		});
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseRepeatingCalendarItemsLabelKey(java.lang.String, java.lang.String, java.lang.String)
	 */
	@Override
	public void reviseRepeatingCalendarItemsLabelKey(final String entityReference, final String oldLabelKey, final String newLabelKey) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				return dao.updateRepeatingCalendarItemsLabelKey(entityReference, oldLabelKey, newLabelKey);
			}
			
		});	
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseRepeatingCalendarItemTime(java.lang.String, java.util.Date, java.util.Date)
	 */
	@Override
	public void reviseRepeatingCalendarItemTime(final String entityReference, final Date newFirstTime, final Date newLastTime) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				return dao.updateRepeatingCalendarItemTime(entityReference, newFirstTime, newLastTime);
			}
			
		});
		
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#reviseRepeatingCalendarItemTitle(java.lang.String, java.lang.String)
	 */
	@Override
	public void reviseRepeatingCalendarItemTitle(final String entityReference, final String newTitle) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				return dao.updateRepeatingCalendarItemTitle(entityReference, newTitle);
			}
			
		});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#scheduleAvailabilityCheck(java.lang.String, java.lang.String, java.util.Date)
	 */
	@Override
	public void scheduleAvailabilityCheck(String entityReference, String entityTypeId, Date scheduledTime) {
		final AvailabilityCheck availabilityCheck = new AvailabilityCheck(entityReference, entityTypeId, scheduledTime);
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				
				return dao.addAvailabilityCheck(availabilityCheck);
			}
			
		});
		
	}

	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#setRepeatingEventHorizon(java.util.Date)
	 */
	@Override
	public void setRepeatingEventHorizon(Date newHorizon) {
		
		this.horizon = new Date(newHorizon.getTime());
	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#updateCalendarLinks(java.lang.String)
	 */
	@Override
	public void updateCalendarLinks(String entityReference) {
		final List<CalendarItem> items = dao.getCalendarItems(entityReference);
		int count = 0;
		if(items != null && items.size() > 0) {
			CalendarItem firstItem = items.get(0);
			DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(firstItem.getSourceType().getIdentifier());
			Set<String> oldUserSet = dao.getSakaIdsForUserWithCalendarLinks(entityReference);
			Set<String> newUserSet = new TreeSet<String>(dashboardEntityInfo.getUsersWithAccess(entityReference));
			
			final Set<String> removeSet = new TreeSet(oldUserSet);
			removeSet.removeAll(newUserSet);
			Set<String> addSet = new TreeSet(newUserSet);
			addSet.removeAll(oldUserSet);

			if(logger.isDebugEnabled()) {
				logger.debug("oldUserSet.size == " + oldUserSet.size());
				logger.debug("newUserSet.size == " + newUserSet.size());
				logger.debug("removeSet.size == " + removeSet.size());
				logger.debug("addSet.size == " + addSet.size());
			}
			final List<CalendarLink> calendarLinks = new ArrayList<CalendarLink>();
			for(String sakaiUserId : addSet) {
				Person person = dao.getPersonBySakaiId(sakaiUserId);
				if(person != null) {
					for(CalendarItem item : items) {
						CalendarLink link = new CalendarLink(person, item, item.getContext(),false, false);
						calendarLinks.add(link);
					}
				}
			}
			count = (Integer) transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					int c = 0;
					for(String sakaiUserId : removeSet) {
						Person person = dao.getPersonBySakaiId(sakaiUserId);
						if(person != null) {
							for(CalendarItem item : items) {
								dao.deleteCalendarLink(person.getId(), item.getId());
							}
						}
					}
					if(calendarLinks.size() > 0) {
						c = dao.addCalendarLinks(calendarLinks);
					}
					return c;
				}
				
			});
			
		}
		if(logger.isDebugEnabled()) {
			StringBuilder buf = new StringBuilder("updateCalendarLinks(");
			buf.append(entityReference);
			buf.append(") added ");
			buf.append(count);
			buf.append(" calendarLinks");
			logger.debug(buf);
		}

	}
	
	/* (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#updateNewsLinks(java.lang.String)
	 */
	@Override
	public void updateNewsLinks(String entityReference) {
		final NewsItem item = dao.getNewsItem(entityReference);
		if(item == null) {
			
		} else {
			DashboardEntityInfo dashboardEntityInfo = this.dashboardEntityInfoMap.get(item.getSourceType().getIdentifier());
			Set<String> oldUserSet = dao.getSakaiIdsForUserWithNewsLinks(entityReference);
			Set<String> newUserSet = new TreeSet<String>(dashboardEntityInfo.getUsersWithAccess(entityReference));
			
			final Set<String> removeSet = new TreeSet(oldUserSet);
			removeSet.removeAll(newUserSet);
			Set<String> addSet = new TreeSet(newUserSet);
			addSet.removeAll(oldUserSet);
			
			if(logger.isDebugEnabled()) {
				logger.debug("oldUserSet.size == " + oldUserSet.size());
				logger.debug("newUserSet.size == " + newUserSet.size());
				logger.debug("removeSet.size == " + removeSet.size());
				logger.debug("addSet.size == " + addSet.size());
			}
			
			final List<NewsLink> newsLinks = new ArrayList<NewsLink>();
			for(String sakaiUserId : addSet) {
				Person person = dao.getPersonBySakaiId(sakaiUserId);
				if(person != null) {
					logger.debug("Attempting to add link for person: " + person);
					NewsLink link = new NewsLink(person, item, item.getContext(),false, false);
					newsLinks.add(link);
				}
			}
			int count = (Integer) transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					int c = 0;
					for(String sakaiUserId : removeSet) {
						Person person = dao.getPersonBySakaiId(sakaiUserId);
						if(person != null) {
							logger.debug("Attempting to remove link for person: " + person);
							dao.deleteNewsLink(person.getId(), item.getId());
						}
					}

					if(newsLinks.size() > 0) {
						c = dao.addNewsLinks(newsLinks);
					}
					return c;
				}
				
			});
		}
	}
	
	/**
	 * @param sakaiId
	 * @return
	 */
	protected Person getOrCreatePerson(String sakaiId) {
		Person person = dao.getPersonBySakaiId(sakaiId);
		if(person == null) {
			User userObj = this.sakaiProxy.getUser(sakaiId);
			final Person new_person = new Person(sakaiId, userObj.getEid());
			person = (Person) transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					dao.addPerson(new_person);
					return dao.getPersonBySakaiId(new_person.getSakaiId());
				}
				
			});
		}
		return person;
	}

	/**
	 * Verify that all properties of a CalendarItem correspond to the values of a RepeatingCalendarItem, 
	 * a sequenceNumber and a calendarTime.  Update any incorrect values in the database.  
	 * @param calendarItem
	 * @param repeatingEvent
	 * @param sequenceNumber
	 * @param calendarTime
	 * @return true if updates have been made, false otherwise.
	 */
	protected boolean verifyCalendarItem(final CalendarItem calendarItem,
			RepeatingCalendarItem repeatingEvent, Integer sequenceNumber, Date calendarTime) {
		
		boolean saveChanges = false;
		if(sequenceNumber != null && sequenceNumber.equals(calendarItem.getSequenceNumber())) {
			// do nothing
		} else {
			calendarItem.setSequenceNumber(sequenceNumber);
			saveChanges = true;
		}
		if(repeatingEvent.getEntityReference() != null && repeatingEvent.getEntityReference().equals(calendarItem.getEntityReference())) {
			// do nothing
		} else {
			calendarItem.setEntityReference(repeatingEvent.getEntityReference());
			saveChanges = true;
		}
		if(calendarTime != null && calendarTime.equals(calendarItem.getCalendarTime())) {
			// do nothing
		} else {
			calendarItem.setCalendarTime(calendarTime);
			saveChanges = true;
		}
		if(repeatingEvent.getTitle() != null && repeatingEvent.getTitle().equals(calendarItem.getTitle())) {
			// do nothing
		} else {
			calendarItem.setTitle(repeatingEvent.getTitle());
			saveChanges = true;
		}
		if(repeatingEvent.getCalendarTimeLabelKey() != null && repeatingEvent.getCalendarTimeLabelKey().equals(calendarItem.getCalendarTimeLabelKey())) {
			// do nothing
		} else {
			calendarItem.setCalendarTimeLabelKey(repeatingEvent.getCalendarTimeLabelKey());
			saveChanges = true;
		}
		if(repeatingEvent.getContext() != null && repeatingEvent.getContext().equals(calendarItem.getContext())) {
			// do nothing
		} else {
			calendarItem.setContext(repeatingEvent.getContext());
			saveChanges = true;
		}
		if(repeatingEvent.getSourceType() != null && repeatingEvent.getSourceType().getIdentifier() != null 
				&& calendarItem.getSourceType() != null && calendarItem.getSourceType().getIdentifier() != null && repeatingEvent.getSourceType().getIdentifier().equals(calendarItem.getSourceType().getIdentifier()) ) {
			// do nothing
		} else {
			calendarItem.setSourceType(repeatingEvent.getSourceType());
			saveChanges = true;
		}
		if(repeatingEvent.getSubtype() != null && repeatingEvent.getSubtype().equals(calendarItem.getSubtype())) {
			// do nothing
		} else {
			calendarItem.setSubtype(repeatingEvent.getSubtype());
			saveChanges = true;
		}
		if(saveChanges) {
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					dao.updateCalendarItem(calendarItem);
					return true;
				}
				
			});
			
		}
		return saveChanges;
	}

	/*
	 * (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#checkTaskLock(java.lang.String)
	 */
	public boolean checkTaskLock(final String task) {
		final Date now = new Date();
		
		String serverId = this.sakaiProxy.getServerId();
		Date expirationTime = taskLockExpirationTimes.get(task);
		if(expirationTime != null) {
			if(expirationTime.after(now)) {
				if(taskLockServerAssignments.get(task) == null) {
					// need to get update value from database
				} else if(taskLockServerAssignments.get(task).equals(serverId)) {
					return true;
				} else {
					return false;
				}
			}
		}

		List<TaskLock> taskLocks = this.dao.getTaskLocks(task);
		if(taskLocks != null && ! taskLocks.isEmpty()) {
			final TaskLock first = taskLocks.get(0);
			Date negotiationDeadline = taskLockNegotiationsDeadlines.get(task);
			if(first.getServerId().equals(serverId)) {
				// this server either has lock or is first in line for lock
				if(first.isHasLock()) {
					// this server has lock
					// update task-lock data
					assignTask(task, serverId);
					
					return true;
				} else if(negotiationDeadline != null && negotiationDeadline.before(now)) {
					// claim the task, update task-lock data, and return true
					transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							
							dao.updateTaskLock(first.getId(), true, now);
							return true;
						}
						
					});
					
					// update task-lock data
					assignTask(task, serverId);
					
					return true;
				} if(first.getClaimTime().before(new Date(now.getTime() - 2*TASK_LOCK_NEGOTIATION_LIMIT_MILLISECONDS))) {
					// this is to deal with restarts in which no server previously claimed the task
					return (Boolean) transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							dao.deleteTaskLocks(task);
							return false;
						}
						
					});
				}
				return false;
			} else if(first.isHasLock()) {
				// some other server has lock
				// check expiration to make sure the lock is active
				if(first.getLastUpdate().before(new Date(now.getTime() - TASK_LOCK_EXPIRATION_PERIOD))) {
					// server with lock is not active. clear all locks for this task so process can start again 
					return (Boolean) transactionTemplate.execute(new TransactionCallback(){

						@Override
						public Object doInTransaction(TransactionStatus status) {
							dao.deleteTaskLocks(task);
							return false;
						}
						
					});

				} else {
					// server is active, but our data needs updating
					assignTask(task, first.getServerId());
					return false;
				}
			} else if (negotiationDeadline != null && negotiationDeadline.before(now)) {
				assignTask(task, first.getServerId());
				return false;
			}
			
			// we're still negotiating.  Need to make sure this server has tried to claim the task.
			for(TaskLock lock : taskLocks) {
				if(lock.getServerId().equals(serverId)) {
					return false;
				}
			}
		}
		
		if(this.delay == null) {
			// the task is not claimed, and this server has not tried to claim the task. do it now.
			// Check whether this server already has any tasks or is in line for tasks. 
			boolean giveSomeoneElseAChance = false;
			List<TaskLock> assignedTasks = this.dao.getAssignedTaskLocks();
			if(assignedTasks != null && !assignedTasks.isEmpty()) {
				for(TaskLock tl : assignedTasks) {
					if(serverId.equals(tl.getServerId())) {
						giveSomeoneElseAChance = true;
						break;
					}
				}
			}
			
			// If it does, wait a bit to give other servers a chance to claim this one first.
			if(giveSomeoneElseAChance) {
				// Skip a turn or two 
				// (i.e. set a delay before we can claim any other task) 
				this.delay = new Date( System.currentTimeMillis() + 5000L );
			} else {
				for(String t : TASKS) {
					List<TaskLock> possibleTasks = this.dao.getTaskLocks(t);
					if(possibleTasks != null && !possibleTasks.isEmpty() && serverId.equals(possibleTasks.get(0).getServerId())) {
						giveSomeoneElseAChance = true;
						break;
					}
				}
			}
		}
		
		if(this.delay == null || this.delay.getTime() < System.currentTimeMillis()) {
			final TaskLock taskLock = new TaskLock(task, serverId, new Date(), false, now);
			transactionTemplate.execute(new TransactionCallback(){

				@Override
				public Object doInTransaction(TransactionStatus status) {
					dao.addTaskLock(taskLock);
					return true;
				}
				
			});
			
			
			this.taskLockNegotiationsDeadlines.put(task, new Date(now.getTime() + TASK_LOCK_NEGOTIATION_LIMIT_MILLISECONDS));
			
			// completely clear the delay so we are not blocked from claiming another task if need be.
			this.delay = null;
		}
		return false;
	}

	/**
	 * @param task
	 * @param first
	 */
	protected void assignTask(String task, String serverId) {
		logger.info("Dashboard task " + task + " being handled by server: " + serverId);
		this.taskLockServerAssignments.put(task, serverId);
		this.taskLockExpirationTimes.put(task, new Date(System.currentTimeMillis() + TASK_LOCK_EXPIRATION_PERIOD));
	}

	/*
	 * (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#updateTaskLock(java.lang.String)
	 */
	public void updateTaskLock(final String task) {
		final String serverId = this.sakaiProxy.getServerId();
		final Date lastUpdate = new Date();
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				dao.updateTaskLock(task, serverId, lastUpdate);
				return true;
			}
			
		});
		
	}

	/*
	 * (non-Javadoc)
	 * @see org.sakaiproject.dash.logic.DashboardLogic#removeTaskLocks(java.lang.String)
	 */
	public void removeTaskLocks(final String task) {
		transactionTemplate.execute(new TransactionCallback(){

			@Override
			public Object doInTransaction(TransactionStatus status) {
				dao.deleteTaskLocks(task);
				return true;
			}
			
		});
	}

}
