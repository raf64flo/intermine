package org.intermine.webservice.server.idresolution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.log4j.Logger;
import org.intermine.api.InterMineAPI;
import org.intermine.api.bag.BagQueryResult;
import org.intermine.api.bag.BagQueryResult.IssueResult;
import org.intermine.api.bag.ConvertedObjectPair;
import org.intermine.api.util.PathUtil;
import org.intermine.metadata.ClassDescriptor;
import org.intermine.metadata.Model;
import org.intermine.model.InterMineObject;
import org.intermine.objectstore.ObjectStoreException;
import org.intermine.pathquery.Path;
import org.intermine.pathquery.PathException;
import org.intermine.util.DynamicUtil;
import org.intermine.web.context.InterMineContext;
import org.intermine.web.logic.config.FieldConfig;
import org.intermine.web.logic.config.FieldConfigHelper;
import org.intermine.web.logic.config.WebConfig;

public class BagResultCategoryKeyFormatter implements BagResultFormatter {
    
    private static final Logger LOG = Logger.getLogger(BagResultCategoryKeyFormatter.class);

    private static final String[] ISSUES = new String[] {
        BagQueryResult.DUPLICATE, BagQueryResult.WILDCARD, BagQueryResult.OTHER, BagQueryResult.TYPE_CONVERTED
    };

    private final InterMineAPI im;

    public BagResultCategoryKeyFormatter(InterMineAPI api) {
        this.im = api;
    }

    @Override
    public Map<String, Object> format(BagQueryResult bqr) {
        final Map<String, Object> ret = new HashMap<String, Object>();

        ret.put("MATCH", getMatches(bqr));
        for (String issue: ISSUES) {
            ret.put(issue, getIssues(issue, bqr));
        }
        ret.put("UNRESOLVED", new ArrayList<String>(bqr.getUnresolvedIdentifiers()));

        return ret;
    }

    private List<Map<String, Object>> getIssues(String issueType, BagQueryResult bqr) {
        final List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (IssueResult issue: bqr.getIssueResults(issueType)) {
            final Map<String, Object> obj = new HashMap<String, Object>();
            final List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            
            obj.put("input", issue.inputIdent);
            obj.put("reason", issue.queryDesc);
            obj.put("matches", matches);

            for (Object match: issue.results) {
                matches.add(processIssueMatch(match));
            }
            result.add(obj);
        }
        return result;
    }

    /*
     * Dispatch by type to the actual processors.
     * Could be more elegant with a map from type -> Processor, but that would be utter overkill.
     */
    private Map<String, Object> processIssueMatch(Object match) {
        final Map<String, Object> matchObj;
        if (match == null) {
            throw new IllegalStateException("null match returned.");
        } else if (match instanceof Integer) {
            matchObj = processMatch((Integer) match);
        } else if (match instanceof InterMineObject) {
            matchObj = processMatch((InterMineObject) match);
        } else if (match instanceof ConvertedObjectPair) {
            matchObj = processMatch((ConvertedObjectPair) match);
        } else {
            throw new IllegalStateException("Cannot process " + match);
        }
        return matchObj;
    }

    private Map<String, Object> processMatch(Integer id) {
        Map<String, Object> matchObj = new HashMap<String, Object>();
        matchObj.put("id", id);
        matchObj.put("summary", getObjectDetails(id));
        return matchObj;
    }

    private Map<String, Object> processMatch(InterMineObject imo) {
        Map<String, Object> matchObj = new HashMap<String, Object>();
        matchObj.put("id", imo.getId());
        matchObj.put("summary", getObjectDetails(imo));
        return matchObj;
    }

    private Map<String, Object> processMatch(ConvertedObjectPair pair) {
        Map<String, Object> matchObj = processMatch(pair.getNewObject());
        Map<String, Object> from = processMatch(pair.getOldObject());
        matchObj.put("from", from);
        return matchObj;
    }

    private List<Map<String, Object>> getMatches(BagQueryResult bqr) {
        final List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Entry<Integer, List> match: bqr.getMatches().entrySet()) {
            Map<String, Object> obj = new HashMap<String, Object>();
            obj.put("id", match.getKey());
            obj.put("input", match.getValue());
            obj.put("summary", getObjectDetails(match.getKey()));
            result.add(obj);
        }
        return result;
    }

    private Map<String, Object> getObjectDetails(Integer objId) {
        InterMineObject imo;
        if (objId == null) throw new IllegalArgumentException("obj cannot be null");
        try {
            imo = im.getObjectStore().getObjectById(objId);
        } catch (ObjectStoreException e) {
            throw new IllegalStateException("Could not retrieve object reported as match", e);
        }
        return getObjectDetails(imo);
    }

    private Map<String, Object> getObjectDetails(InterMineObject imo) {
        WebConfig webConfig = InterMineContext.getWebConfig();
        Model m = im.getModel();
        Map<String, Object> objectDetails = new HashMap<String, Object>();
        String className = DynamicUtil.getSimpleClassName(imo.getClass());
        ClassDescriptor cd = m.getClassDescriptorByName(className);
        objectDetails.put("class", cd.getUnqualifiedName());
        for (FieldConfig fc : FieldConfigHelper.getClassFieldConfigs(webConfig, cd)) {
            try {
                Path p = new Path(m, cd.getUnqualifiedName() + "." + fc.getFieldExpr());
                if (p.endIsAttribute() && fc.getShowInSummary()) {
                    objectDetails.put(
                            p.getNoConstraintsString().replaceAll("^[^.]*\\.", ""),
                            PathUtil.resolvePath(p, imo));
                }
            } catch (PathException e) {
                LOG.error("Configuration error", e);
            }
        }
        return objectDetails;
    }

}
