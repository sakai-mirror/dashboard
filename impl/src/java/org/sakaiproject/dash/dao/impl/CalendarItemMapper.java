/**
 * 
 */
package org.sakaiproject.dash.dao.impl;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sakaiproject.dash.model.CalendarItem;
import org.sakaiproject.dash.model.Context;
import org.sakaiproject.dash.model.RepeatingCalendarItem;
import org.sakaiproject.dash.model.SourceType;
import org.springframework.jdbc.core.RowMapper;

/**
 * 
 *
 */
public class CalendarItemMapper implements RowMapper {
	
	private static Log logger = LogFactory.getLog(CalendarItemMapper.class);

	/* (non-Javadoc)
	 * @see org.springframework.jdbc.core.RowMapper#mapRow(java.sql.ResultSet, int)
	 */
	public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
		
		CalendarItem calendarItem = new CalendarItem();
		calendarItem.setId(rs.getLong("ci_id"));
		calendarItem.setCalendarTime(rs.getTimestamp("ci_calendar_time"));
		calendarItem.setCalendarTimeLabelKey(rs.getString("ci_calendar_time_label_key"));
		calendarItem.setTitle(rs.getString("ci_title"));
		calendarItem.setEntityReference(rs.getString("ci_entity_ref"));
		calendarItem.setSubtype(rs.getString("ci_subtype"));
		
		// repeating_event_id
		RepeatingCalendarItem repeatingCalendarItem = (RepeatingCalendarItem) (new RepeatingCalendarItemMapper()).mapRow(rs, rowNum);
		calendarItem.setRepeatingCalendarItem(repeatingCalendarItem);
		
		// source_type
		SourceType sourceType = (SourceType) (new SourceTypeMapper()).mapRow(rs, rowNum);
		calendarItem.setSourceType(sourceType);
		
		// context
		Context context = (Context) (new ContextMapper()).mapRow(rs, rowNum);
		calendarItem.setContext(context);
		
		logger.info(calendarItem);
		
		return calendarItem;
	}

}
