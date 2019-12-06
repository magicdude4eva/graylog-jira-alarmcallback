package com.bidorbuy.graylog.alarmcallbacks.jira;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.graylog2.plugin.alarms.AlertCondition;
import org.graylog2.plugin.alarms.callbacks.AlarmCallbackException;
import org.graylog2.plugin.streams.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.rcarz.jiraclient.BasicCredentials;
import net.rcarz.jiraclient.Field;
import net.rcarz.jiraclient.Issue;
import net.rcarz.jiraclient.Issue.FluentCreate;
import net.rcarz.jiraclient.JiraClient;
import net.rcarz.jiraclient.JiraException;
import net.sf.json.JSONObject;

/* package */ final class JiraIssueClient
{
	private static final Logger LOG = LoggerFactory.getLogger(JiraAlarmCallback.class);

	// The JIRA field-name for the MD5 - digest
	protected static final String CONST_GRAYLOGMD5_DIGEST = "graylog_md5";

	private final String JIRAServerURL;
	private final String JIRAUserName;
	private final String JIRAPassword;

	private final String JIRAProjectKey;
	private final String JIRATitle;
	private final String JIRADescription;
	private final boolean JIRADescriptionAsComments;
	private final String JIRALabels;
	private final String JIRAIssueType;
	private final String JIRAComponents;
	private final String JIRAPriority;
	private final String JIRAMD5CustomFieldName;
	private final boolean JIRAMD5History;
	/** The JIRA field that keeps track of the occurrence count. */
	private final String JIRACounterCustomFieldName;
	private final String JIRADuplicateIssueFilterQuery;

	private final Map<String, String> JIRAGraylogMapping;
	private final String JIRAMessageDigest;
	private final Object jiraClientMonitor = new Object();
	private JiraClient jiraClient;

	/* package */ JiraIssueClient(
			final String JIRAProjectKey,
			final String JIRATitle,
			final String JIRADescription,
			final boolean JIRADescriptionAsComments,
			final String JIRALabels,
			final String JIRAIssueType,
			final String JIRAComponents,
			final String JIRAPriority,
			final String JIRAServerURL,
			final String JIRAUserName,
			final String JIRAPassword,
			final String JIRADuplicateIssueFilterQuery,
			final String JIRAMD5CustomFieldName,
			final boolean JIRAMD5History,
			final String JIRACounterCustomFieldName,
			final Map<String, String> JIRAGraylogMapping,
			final String JIRAMessageDigest
	)
	{
		this.JIRAProjectKey					= JIRAProjectKey;
		this.JIRATitle						= JIRATitle;
		this.JIRADescription				= JIRADescription;
		this.JIRADescriptionAsComments		= JIRADescriptionAsComments;
		this.JIRALabels						= JIRALabels;
		this.JIRAIssueType					= JIRAIssueType;
		this.JIRAComponents					= JIRAComponents;
		this.JIRAPriority					= JIRAPriority;
		this.JIRAServerURL					= JIRAServerURL;
		this.JIRAUserName					= JIRAUserName;
		this.JIRAPassword					= JIRAPassword;
		this.JIRAGraylogMapping				= JIRAGraylogMapping;
		this.JIRAMessageDigest				= JIRAMessageDigest;
		this.JIRAMD5CustomFieldName			= JIRAMD5CustomFieldName;
		this.JIRAMD5History					= JIRAMD5History;
		this.JIRACounterCustomFieldName		= JIRACounterCustomFieldName;
		this.JIRADuplicateIssueFilterQuery	= JIRADuplicateIssueFilterQuery == null ? ""
				: JIRADuplicateIssueFilterQuery.trim();
	}

	/* package */ void trigger(final Stream stream, final AlertCondition.CheckResult checkResult)
			throws AlarmCallbackException
	{
		try
		{
			final Issue issue = getExistingJiraIssue();

			if (issue == null)
			{
				createJIRAIssue();
			}
			else
			{
				addComment(issue);
				updateActiveJIRAIssueOccurrenceCount(issue);
			}
		}
		catch (final Throwable ex)
		{
			LOG.error("Error in trigger function" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw ex;
		}
	}

	private JiraClient getJiraClient()
	{
		synchronized (jiraClientMonitor)
		{
			if (jiraClient == null)
			{
				final BasicCredentials creds = new BasicCredentials(JIRAUserName, JIRAPassword);
				jiraClient = new JiraClient(JIRAServerURL, creds);
			}

			return jiraClient;
		}
	}

	private Issue getExistingJiraIssue() throws AlarmCallbackException
	{
		if (StringUtils.isBlank(JIRAMessageDigest))
		{
			return null;
		}

		try
		{
			// Search for duplicate issues
			final String jql = "project = " + JIRAProjectKey + " " + JIRADuplicateIssueFilterQuery + " " + " AND ("
					+ CONST_GRAYLOGMD5_DIGEST + " ~ \"" + JIRAMessageDigest + "\" OR" + " description ~ \""
					+ JIRAMessageDigest + "\")";

			final String baseAttributes = "id,key,summary";
			final String attrs = StringUtils.isBlank(JIRACounterCustomFieldName) ? baseAttributes
					: baseAttributes + ',' + JIRACounterCustomFieldName;

			final Issue.SearchResult srJiraIssues = getJiraClient().searchIssues(jql, attrs, 2);
			final int nbMatches = srJiraIssues != null && srJiraIssues.issues != null ? srJiraIssues.issues.size() : 0;

			if (nbMatches == 0)
			{
				LOG.info("No existing open JIRA issue for MD5=" + JIRAMessageDigest + ".\nJQL => " + jql);
				return null;
			}

			final Issue issue = srJiraIssues.issues.get(0);
			final String issueKey = issue.getKey();

			if (nbMatches == 1)
			{
				LOG.info("There is one issue " + issueKey + " for MD5=" + JIRAMessageDigest + ".\nJQL => " + jql);
			}
			else
			{
				LOG.warn("There is more than one issue with  for MD5=" + JIRAMessageDigest + ". We picked " + issueKey
						+ ".\nJQL => " + jql);
			}

			return issue;
		}
		catch (JiraException ex)
		{
			LOG.error("Error searching for JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed searching for duplicate issue", ex);
		}
		catch (Throwable ex)
		{
			LOG.error("Error searching for JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed searching for duplicate issue", ex);
		}
	}

	/**
	 * This method converts the passed in comma-separated list of items into a {@link List} of {@link String strings}.
	 *
	 * @param str
	 * @return
	 */
	private static List<String> commaSplit(final String str)
	{
		return StringUtils.isNotBlank(str) ? Arrays.asList(StringUtils.split(str, ',')) : null;
	}

	/**
	 * Create a JIRA issue.
	 *
	 * @param jiraIssue
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("serial")
	private void createJIRAIssue() throws AlarmCallbackException
	{
		try
		{
			final String history;

			if (JIRAMD5History)
			{
				// We're configured to generate a history.
				// What this means is that we query all Jira tickets without the user-defined filter.
				// If issues are returned, then we generate a string that we append to the issue description.
				// This string enumerates all the ticket keys as well as their counter value (if such a field
				// was configured on the plugin by the user).
				//
				// TODO should we prevent generating the history if JIRADuplicateIssueFilterQuery.isEmpty() == true?
				final Map<String, Integer> issues = getHistory();

				if (issues.isEmpty())
				{
					history = "";
				}
				else
				{
					final StringBuilder sb = new StringBuilder("\n\nHistory : ");

					for (final Map.Entry<String, Integer> entry : issues.entrySet())
					{
						final Integer val = entry.getValue();

						sb.append(entry.getKey());

						if (val == null)
						{
							sb.append(' ');
						}
						else
						{
							sb.append(" [").append(val).append("] ");
						}
					}

					history = sb.toString();
				}
			}
			else
			{
				history = "";
			}

			final List<String> labels = commaSplit(JIRALabels);
			final List<String> components = commaSplit(JIRAComponents);

			// We create the base issue and then chain all the required fields
			final FluentCreate fluentIssueCreate = getJiraClient().createIssue(JIRAProjectKey, JIRAIssueType);

			// add JIRA priority
			fluentIssueCreate.field(Field.PRIORITY, JIRAPriority);

			// add assignee - unsure
			// fluentIssueCreate.field(Field.ASSIGNEE, null);

			// add summary / title
			fluentIssueCreate.field(Field.SUMMARY, JIRATitle);

			// add labels
			if (labels != null && !labels.isEmpty())
			{
				fluentIssueCreate.field(Field.LABELS, labels);
			}

			// add components
			if (components != null && !components.isEmpty())
			{
				fluentIssueCreate.field(Field.COMPONENTS, components);
			}

			String strJIRADescription = JIRADescription;

			// add the MD5 digest
			if (StringUtils.isNotBlank(JIRAMessageDigest))
			{
				String md5Field = JIRAMD5CustomFieldName;

				// if we do not have a configured custom-field, we will try and find it from
				// meta-data
				// this requires that the JIRA user has edit-permissions
				if (StringUtils.isBlank(md5Field))
				{
					md5Field = getJIRACustomMD5Field();
				}

				if (StringUtils.isNotBlank(md5Field))
				{
					fluentIssueCreate.field(md5Field, JIRAMessageDigest);
				}
				else
				{
					// If there is no MD5 field defined, we inline the MD5-digest into the JIRA
					// description
					strJIRADescription = "\n\n" + CONST_GRAYLOGMD5_DIGEST + "=" + JIRAMessageDigest + "\n\n";
					LOG.warn("It is more efficient to configure '" + JiraAlarmCallback.CK_JIRA_MD5_CUSTOM_FIELD
							+ "' for MD5-hashing instead of embedding the hash in the JIRA description!");
				}
			}

			// add description - we add this last, as the description could have been
			// modified due to the MD5 inlining above
			fluentIssueCreate.field(Field.DESCRIPTION, strJIRADescription + history);

			if (StringUtils.isNotBlank(JIRACounterCustomFieldName))
			{
				// add count
				fluentIssueCreate.field(JIRACounterCustomFieldName, 1);
			}
			else
			{
				LOG.info("The parameter '" + JiraAlarmCallback.CK_JIRA_COUNTER_CUSTOM_FIELD_DESC
						+ "' is undefined so we will not be tracking the number of occurrences of this problem.");
			}

			// append auto-mapped fields
			if (JIRAGraylogMapping != null && !JIRAGraylogMapping.isEmpty())
			{
				for (final Map.Entry<String, String> arg : JIRAGraylogMapping.entrySet())
				{
					if (StringUtils.isNotBlank(arg.getKey()) && StringUtils.isNotBlank(arg.getValue().toString()))
					{
						String JIRAFieldName = arg.getKey();
						Object JIRAFiedValue = arg.getValue().toString();
						if (JIRAFieldName.endsWith("#i"))
						{
							JIRAFieldName	= JIRAFieldName.substring(0, JIRAFieldName.length() - 2);
							JIRAFiedValue	= new ArrayList<String>()
											{
												{
													add(arg.getValue().toString());
												}
											};
						}

						LOG.info("JIRA/Graylog automap - JIRA-key=" + JIRAFieldName + ", value="
								+ JIRAFiedValue.toString());
						fluentIssueCreate.field(JIRAFieldName, JIRAFiedValue);
					}
				}
			}

			// finally create the issue
			final Issue newIssue = fluentIssueCreate.execute();

			LOG.info("Created new issue " + newIssue.getKey() + " for project " + JIRAProjectKey);
		}
		catch (JiraException ex)
		{
			LOG.error("Error creating JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed creating new issue", ex);
		}
		catch (Throwable ex)
		{
			LOG.error("Error creating JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed creating new issue", ex);
		}

		return;
	}

	/**
	 * This method queries all the {link Issue issues} matching the MD5, but without applying the filter
	 * that the user might have configured on the plugin.
	 *
	 * @return The matching {@link Issue issues}. The key is the {@link Issue}'s key, and the value is the
	 *			{@link Issue}'s counter (if one was configured by the user).
	 *
	 * @throws AlarmCallbackException
	 */
	private Map<String, Integer> getHistory() throws AlarmCallbackException
	{
		try
		{
			// Search for duplicate issues
			final String jql = "project = " + JIRAProjectKey + " AND (" + CONST_GRAYLOGMD5_DIGEST + " ~ \""
					+ JIRAMessageDigest + "\" OR" + " description ~ \"" + JIRAMessageDigest + "\")";

			final String baseAttributes = "id,key,summary";
			final boolean hasCounterAttr = StringUtils.isNotBlank(JIRACounterCustomFieldName);
			final String attrs = hasCounterAttr ? baseAttributes + ',' + JIRACounterCustomFieldName : baseAttributes;

			final Issue.SearchResult srJiraIssues = getJiraClient().searchIssues(jql, attrs, null);
			final int nbMatches = srJiraIssues != null && srJiraIssues.issues != null ? srJiraIssues.issues.size() : 0;

			if (nbMatches == 0)
			{
				LOG.info("No existing open JIRA issue for MD5=" + JIRAMessageDigest + ".\nJQL => " + jql);
				return Collections.emptyMap();
			}

			final Map<String, Integer> rtrn = new TreeMap<>();

			for (final Issue issue : srJiraIssues.issues)
			{
				rtrn.put(issue.getKey(), hasCounterAttr ? getOccurrenceCount(issue) : null);
			}

			return Collections.unmodifiableMap(rtrn);
		}
		catch (JiraException ex)
		{
			LOG.error("Error searching for JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed searching for duplicate issue", ex);
		}
		catch (Throwable ex)
		{
			LOG.error("Error searching for JIRA issue=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed searching for duplicate issue", ex);
		}
	}

	/**
	 * Return the name of the md5 custom field
	 *
	 * @param jira
	 * @param jiraIssue
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	private String getJIRACustomMD5Field() throws AlarmCallbackException
	{
		LOG.warn("It is more efficient to configure '" + JiraAlarmCallback.CK_JIRA_MD5_CUSTOM_FIELD
				+ "' for MD5-hashing.");

		try
		{
			final JSONObject customfields = Issue.getCreateMetadata(getJiraClient().getRestClient(), JIRAProjectKey,
					JIRAIssueType);

			for (Iterator<String> iterator = customfields.keySet().iterator(); iterator.hasNext();)
			{
				String key = iterator.next();

				if (key.startsWith("customfield_"))
				{
					JSONObject metaFields = customfields.getJSONObject(key);
					if (metaFields.has("name")
							&& JiraIssueClient.CONST_GRAYLOGMD5_DIGEST.equalsIgnoreCase(metaFields.getString("name")))
					{
						return key;
					}
				}
			}
			return null;
		}
		catch (JiraException ex)
		{
			LOG.error("Error getting JIRA custom MD5 field=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed retrieving MD5-field", ex);
		}
	}

	/**
	 * This method increments the {@link JiraAlarmCallback#CK_JIRA_COUNTER_CUSTOM_FIELD counter} field (as configured
	 * by the user) on the passed {@link Issue}. <p>
	 *
	 * If the user has not configured a counter field in the plugin, nothing is updated.
	 *
	 * @param issues
	 * @return
	 * @throws JiraException
	 * @throws Exception
	 */
	private void updateActiveJIRAIssueOccurrenceCount(final Issue issue) throws AlarmCallbackException
	{
		if (StringUtils.isBlank(JIRACounterCustomFieldName))
		{
			LOG.info("The parameter '" + JiraAlarmCallback.CK_JIRA_COUNTER_CUSTOM_FIELD_DESC
					+ "' is undefined so we will not be incrementing the number of occurrences of this problem.");
			return;
		}

		final int newCount = getOccurrenceCount(issue) + 1;

		try
		{
			issue.update().field(JIRACounterCustomFieldName, newCount).execute();
			LOG.info("update issue " + issue.getKey() + " for project " + JIRAProjectKey + " with an updated count of "
					+ newCount);
		}
		catch (JiraException ex)
		{
			LOG.error("Error updating JIRA count field=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed updating JIRA issue count", ex);
		}
	}

	/**
	 * This method extracts from the passed in {@link Issue} the value of the
	 * {@link JiraAlarmCallback#CK_JIRA_COUNTER_CUSTOM_FIELD counter} field as configured by the user. <p>
	 *
	 * If no such field was configured by the user, or if the {@link Issue} was never set one, zero is returned.
	 *
	 * @param issue
	 * @return
	 */
	private int getOccurrenceCount(final Issue issue)
	{
		final Object fieldValue = issue.getField(JIRACounterCustomFieldName);

		if (fieldValue == null)
		{
			return 1;
		}

		if (fieldValue instanceof Number)
		{
			return ((Number) fieldValue).intValue();
		}

		if (fieldValue instanceof String)
		{
			final String str = ((String) fieldValue).trim();
			if (str.isEmpty())
			{
				return 1;
			}

			try
			{
				return Integer.parseInt(str);
			}
			catch (final NumberFormatException e)
			{
				LOG.warn("The parameter '" + JiraAlarmCallback.CK_JIRA_COUNTER_CUSTOM_FIELD_DESC
						+ "' has a non-integer string value \"" + str + "\". Defaulting to zero.", e);
				return 1;
			}
		}

		LOG.warn("The parameter '" + JiraAlarmCallback.CK_JIRA_COUNTER_CUSTOM_FIELD_DESC + "' has a non-integer value ("
				+ fieldValue.getClass().getName() + "). Defaulting to zero.");
		return 1;
	}

	/**
	 * Add a comment to the passed in JIRA issue. This comment will contain the description that was computed
	 * using the information found in the graylog event. <p>
	 *
	 * The comment will be added only if the user has configured the plug in accordingly by checking the box
	 * labeled with {@link JiraAlarmCallback#CK_JIRA_MESSAGE_TEMPLATE_COMMENT JIRA message template as comments}.
	 *
	 * @param issue
	 * @throws AlarmCallbackException
	 */
	private void addComment(final Issue issue) throws AlarmCallbackException
	{
		if (!JIRADescriptionAsComments)
			return;

		try
		{
			issue.addComment(JIRADescription);
		}
		catch (JiraException ex)
		{
			final String msg = "Error adding JIRA comment to ticket " + issue.getKey();
			LOG.error(msg, ex);
			throw new AlarmCallbackException(msg, ex);
		}
	}
}