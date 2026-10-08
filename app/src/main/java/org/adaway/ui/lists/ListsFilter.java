package org.adaway.ui.lists;

/**
 * This class represents the filter to apply to host lists.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class ListsFilter {
    public static final ListsFilter ALL = new ListsFilter("");
    /**
     * The query filter to apply to hosts name (wildcard based).
     */
    public final String query;
    /**
     * The query filter to apply to hosts name (sql like format).
     */
    public final String sqlQuery;

    public ListsFilter(String query) {
        this.query = query;
        this.sqlQuery = convertToLikeQuery(query);
    }

    private static String convertToLikeQuery(String query) {
        return "%" + query.replace("*", "%")
                .replace("?", "_") + "%";
    }
}
