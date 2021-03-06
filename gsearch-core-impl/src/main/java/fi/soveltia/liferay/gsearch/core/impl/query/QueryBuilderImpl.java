
package fi.soveltia.liferay.gsearch.core.impl.query;

import com.liferay.portal.configuration.metatype.bnd.util.ConfigurableUtil;
import com.liferay.portal.kernel.json.JSONArray;
import com.liferay.portal.kernel.json.JSONFactoryUtil;
import com.liferay.portal.kernel.json.JSONObject;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.BooleanClauseOccur;
import com.liferay.portal.kernel.search.BooleanQuery;
import com.liferay.portal.kernel.search.Query;
import com.liferay.portal.kernel.search.filter.BooleanFilter;
import com.liferay.portal.kernel.search.generic.BooleanQueryImpl;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Map;

import javax.portlet.PortletRequest;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import fi.soveltia.liferay.gsearch.core.api.query.QueryBuilder;
import fi.soveltia.liferay.gsearch.core.api.query.QueryParams;
import fi.soveltia.liferay.gsearch.core.api.query.builder.MatchQueryBuilder;
import fi.soveltia.liferay.gsearch.core.api.query.builder.QueryStringQueryBuilder;
import fi.soveltia.liferay.gsearch.core.api.query.builder.WildcardQueryBuilder;
import fi.soveltia.liferay.gsearch.core.api.query.ct.CTQueryBuilder;
import fi.soveltia.liferay.gsearch.core.api.query.filter.QueryFilterBuilder;
import fi.soveltia.liferay.gsearch.core.configuration.GSearchConfiguration;
import fi.soveltia.liferay.gsearch.query.QueryStringQuery;

/**
 * Query builder implementation.
 * 
 * @author Petteri Karttunen
 */
@Component(
	configurationPid = "fi.soveltia.liferay.gsearch.core.configuration.GSearchConfiguration", 
	immediate = true, 
	service = QueryBuilder.class
)
public class QueryBuilderImpl implements QueryBuilder {

	/**
	 * {@inheritDoc}
	 */
	@Override
	public BooleanQuery buildQuery(
		PortletRequest portletRequest, QueryParams queryParams)
		throws Exception {
		
		// Build query

		BooleanQuery query = constructQuery(portletRequest, queryParams);
		
		// Add Audience targeting query
		
		if (_gSearchConfiguration.enableAudienceTargeting()) {
			addCTQuery(portletRequest, query);
		}		
		
		// Add filters

		BooleanFilter preBooleanFilter =
			_queryFilterBuilder.buildQueryFilter(portletRequest, queryParams);
		
		query.setPreBooleanFilter(preBooleanFilter);

		// Set query config
		
		setQueryConfig(query);

		return query;
	}

	@Activate
	@Modified
	protected void activate(Map<String, Object> properties) {
		_gSearchConfiguration = ConfigurableUtil.createConfigurable(
			GSearchConfiguration.class, properties);
	}	
		
	/**
	 * Add Audience targeting query.
	 * 
	 * @param portletRequest
	 * @param query
	 * @throws Exception
	 */
	protected void addCTQuery(PortletRequest portletRequest, BooleanQuery query) throws Exception {

		if (_ctQueryBuilder == null) {
			_log.error("Audience targeting is enable but the gsearch-audience-targeting module " +
				"seems not to be installed.");
		} else {
	
			BooleanQuery ctQuery = _ctQueryBuilder.buildCTQuery(portletRequest);
	
			if (ctQuery != null) {
				query.add(ctQuery, BooleanClauseOccur.SHOULD);
			}
		}				
	}
	
	/**
	 * Construct query.
	 * 
	 * Please note that QueryStringQuery type is an
	 * extension of Liferay StringQuery. Thus, if you don't want to use
	 * the custom search adapter, this falls silently to the default StringQuery.
	 * Remember however that with standard adapter you loose the possibility to
	 * define target fields or boosts (configuration) - or, they just don't get applied.
	 * 
	 * @param portletRequest
	 * @param queryParams
	 * @return
	 * @throws Exception
	 */
	protected BooleanQuery constructQuery(PortletRequest portletRequest, QueryParams queryParams) throws Exception {
		
		BooleanQuery query = new BooleanQueryImpl();
		
		// Build query
		
		JSONArray configurationArray = JSONFactoryUtil.createJSONArray(
			_gSearchConfiguration.searchFieldConfiguration());
		
		Query subQuery;

		for (int i = 0; i < configurationArray.length(); i++) {
			
			JSONObject queryItem = configurationArray.getJSONObject(i);

			subQuery =  null;

			String queryType = queryItem.getString("queryType");
			String occurString = queryItem.getString("queryType");

			BooleanClauseOccur occur;
			if ("MUST".equals(occurString)) {
				occur = BooleanClauseOccur.MUST;
			} else if ("MUST_NOT".equals(occurString)) {
				occur = BooleanClauseOccur.MUST_NOT;
			} else {
				occur = BooleanClauseOccur.SHOULD;
			}
	 		
			if ("match".equals(queryType)) {

				boolean isLocalized = queryItem.getBoolean("localized");

				if (isLocalized) {
					subQuery = _matchQueryBuilder.buildLocalizedQuery(queryItem, queryParams);
				} else {
					subQuery =  _matchQueryBuilder.buildQuery(queryItem, queryParams);
				}
			} else if ("wildcard".equals(queryType)) {
				
				String keywordSplitter = queryItem.getString("keywordSplitter");

				if (keywordSplitter != null && keywordSplitter.length() > 0) {

					subQuery = _wildcardQueryBuilder.buildSplittedQuery(queryItem, queryParams);
				} else {
					
					subQuery = _wildcardQueryBuilder.buildQuery(queryItem, queryParams);
				}
				
			} else if ("query_string".equals(queryType)) {
				subQuery = (QueryStringQuery)_queryStringQueryBuilder.buildQuery(queryItem, queryParams);
			}
			
			if (subQuery != null) {
				query.add(subQuery,occur);
			}
		}
		return query;
	}	

	@Reference(
		cardinality = ReferenceCardinality.OPTIONAL,
		policyOption = ReferencePolicyOption.GREEDY,
		unbind = "-"
	)
	protected void setCTQueryBuilder(CTQueryBuilder ctQueryBuilder) {

		_ctQueryBuilder = ctQueryBuilder;
	}

	@Reference(unbind = "-")
	protected void setMatchQueryBuilder(MatchQueryBuilder matchQueryBuilder) {

		_matchQueryBuilder = matchQueryBuilder;
	}

	@Reference(unbind = "-")
	protected void setQueryFilterBuilder(QueryFilterBuilder queryFilterBuilder) {

		_queryFilterBuilder = queryFilterBuilder;
	}

	@Reference(unbind = "-")
	protected void setQueryStringQueryBuilder(QueryStringQueryBuilder queryStringQueryBuilder) {

		_queryStringQueryBuilder = queryStringQueryBuilder;
	}

	@Reference(unbind = "-")
	protected void setWildcardQueryBuilder(WildcardQueryBuilder wildcardQueryBuilder) {

		_wildcardQueryBuilder = wildcardQueryBuilder;
	}

	/**
	 * Set queryconfig.
	 * 
	 * @param query
	 */
	protected void setQueryConfig(BooleanQuery query) {

		// Create Queryconfig.

		// QueryConfig queryConfig = new QueryConfig();
		// query.setQueryConfig(queryConfig);
	}

	public static final DateFormat INDEX_DATE_FORMAT =
					new SimpleDateFormat("yyyyMMddHHmmss");

	protected volatile GSearchConfiguration _gSearchConfiguration;

	private CTQueryBuilder _ctQueryBuilder;

	private MatchQueryBuilder _matchQueryBuilder;

	private QueryFilterBuilder _queryFilterBuilder;
	
	private QueryStringQueryBuilder _queryStringQueryBuilder;

	private WildcardQueryBuilder _wildcardQueryBuilder;

	private static final Log _log =
		LogFactoryUtil.getLog(QueryBuilderImpl.class);
}
