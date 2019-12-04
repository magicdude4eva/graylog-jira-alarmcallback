package com.bidorbuy.graylog.alarmcallbacks.jira;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

public class JiraIssueClient
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
	private final String JIRALabels;
	private final String JIRAIssueType;
	private final String JIRAComponents;
	private final String JIRAPriority;
	private final String JIRAMD5CustomFieldName;
	private final String JIRADuplicateIssueFilterQuery;

	private final Map<String, String> JIRAGraylogMapping;
	private final String JIRAMessageDigest;
	private JiraClient jiraClient = null;

	public JiraIssueClient(
			final String JIRAProjectKey,
			final String JIRATitle,
			final String JIRADescription,
			final String JIRALabels,
			final String JIRAIssueType,
			final String JIRAComponents,
			final String JIRAPriority,
			final String JIRAServerURL,
			final String JIRAUserName,
			final String JIRAPassword,
			final String JIRADuplicateIssueFilterQuery,
			final String JIRAMD5CustomFieldName,
			final Map<String, String> JIRAGraylogMapping,
			final String JIRAMessageDigest
	)
	{
		this.JIRAProjectKey = JIRAProjectKey;
		this.JIRATitle = JIRATitle;
		this.JIRADescription = JIRADescription;
		this.JIRALabels = JIRALabels;
		this.JIRAIssueType = JIRAIssueType;
		this.JIRAComponents = JIRAComponents;
		this.JIRAPriority = JIRAPriority;
		this.JIRAServerURL = JIRAServerURL;
		this.JIRAUserName = JIRAUserName;
		this.JIRAPassword = JIRAPassword;
		this.JIRAGraylogMapping = JIRAGraylogMapping;
		this.JIRAMessageDigest = JIRAMessageDigest;
		this.JIRAMD5CustomFieldName = JIRAMD5CustomFieldName;
		this.JIRADuplicateIssueFilterQuery = JIRADuplicateIssueFilterQuery;
	}

	public void trigger(final Stream stream, final AlertCondition.CheckResult checkResult) throws AlarmCallbackException
	{
		try
		{
			BasicCredentials creds = new BasicCredentials(JIRAUserName, JIRAPassword);

			jiraClient = new JiraClient(JIRAServerURL, creds);

			if (isDuplicateJiraIssue() == false)
			{
				createJIRAIssue();
			}
		}
		catch (Throwable ex)
		{
			LOG.error("Error in trigger function" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw ex;
		}
	}

	/**
	 * Checks if a JIRA issue is duplicated.
	 *
	 * @return
	 * @throws Exception
	 */
	private boolean isDuplicateJiraIssue() throws AlarmCallbackException
	{
		boolean bDuplicateIssue = false;

		if (StringUtils.isBlank(JIRAMessageDigest))
		{
			return false;
		}

		try
		{
			// Search for duplicate issues
			final Issue.SearchResult srJiraIssues = jiraClient.searchIssues("project = " + JIRAProjectKey
					+ (JIRADuplicateIssueFilterQuery != null && !JIRADuplicateIssueFilterQuery.isEmpty()
							? " " + JIRADuplicateIssueFilterQuery + " "
							: "")
					+ " AND (" + CONST_GRAYLOGMD5_DIGEST + " ~ \"" + JIRAMessageDigest + "\" OR" + " description ~ \""
					+ JIRAMessageDigest + "\")", "id,key,summary", 1);

			if (srJiraIssues != null && srJiraIssues.issues != null && srJiraIssues.issues.isEmpty() == false)
			{
				bDuplicateIssue = true;
				LOG.info("There "
						+ (srJiraIssues.issues.size() > 1 ? "are " + srJiraIssues.issues.size() + " issues"
								: "is one issue")
						+ " with MD5=" + JIRAMessageDigest
						+ (StringUtils.isNotBlank(JIRADuplicateIssueFilterQuery)
								? " and filter-query='" + JIRADuplicateIssueFilterQuery + "'"
								: ""));
			}
			else
			{
				LOG.info("No existing open JIRA issue for MD5=" + JIRAMessageDigest
						+ (StringUtils.isNotBlank(JIRADuplicateIssueFilterQuery)
								? " and filter-query='" + JIRADuplicateIssueFilterQuery + "'"
								: ""));
			}
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

		return bDuplicateIssue;
	}

	/**
	 * Create a JIRA issue
	 *
	 * @param jiraIssue
	 * @return
	 * @throws Exception
	 */
	@SuppressWarnings("serial")
	public void createJIRAIssue() throws AlarmCallbackException
	{
		try
		{
			final List<String> labels = (StringUtils.isNotBlank(JIRALabels)
					? Arrays.asList(StringUtils.split(JIRALabels, ','))
					: null);
			final List<String> components = (StringUtils.isNotBlank(JIRAComponents)
					? Arrays.asList(StringUtils.split(JIRAComponents, ','))
					: null);

			// We create the base issue and then chain all the required fields
			FluentCreate fluentIssueCreate = jiraClient.createIssue(JIRAProjectKey, JIRAIssueType);

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
			fluentIssueCreate.field(Field.DESCRIPTION, strJIRADescription);

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
							JIRAFieldName = JIRAFieldName.substring(0, JIRAFieldName.length() - 2);
							JIRAFiedValue = new ArrayList<String>()
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
			Issue newIssue = fluentIssueCreate.execute();

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
		String strJIRACustomMD5Field = null;

		LOG.warn("It is more efficient to configure '" + JiraAlarmCallback.CK_JIRA_MD5_CUSTOM_FIELD
				+ "' for MD5-hashing.");

		try
		{
			JSONObject customfields = Issue.getCreateMetadata(jiraClient.getRestClient(), JIRAProjectKey,
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
						strJIRACustomMD5Field = key;
						break;
					}
				}
			}
		}
		catch (JiraException ex)
		{
			LOG.error("Error getting JIRA custom MD5 field=" + ex.getMessage()
					+ (ex.getCause() != null ? ", Cause=" + ex.getCause().getMessage() : ""), ex);
			throw new AlarmCallbackException("Failed retrieving MD5-field", ex);
		}

		return strJIRACustomMD5Field;
	}
}
