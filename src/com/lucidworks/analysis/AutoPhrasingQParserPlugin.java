package com.lucidworks.analysis;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.ResourceLoader;
import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.lucene.analysis.util.WordlistLoader;
import org.apache.lucene.util.Version;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;


public class AutoPhrasingQParserPlugin extends QParserPlugin implements ResourceLoaderAware {

    private static final Logger Log = LoggerFactory.getLogger(AutoPhrasingQParserPlugin.class);
    private CharArraySet phraseSets;

    private AutoPhrasingParameters autoPhrasingParameters;

    @Override
    public void init(NamedList initArgs) {
        Log.info("init ...");
        SolrParams solrParams = SolrParams.toSolrParams(initArgs);
        autoPhrasingParameters = new AutoPhrasingParameters(solrParams);
    }

    @Override
    public QParser createParser(String qStr, SolrParams localParams, SolrParams params,
                                SolrQueryRequest req) {
        Log.info("createParser");
        ModifiableSolrParams modifiableSolrParams = new ModifiableSolrParams(params);
        String modQ = filter(qStr);

        modifiableSolrParams.set("q", modQ);
        return req.getCore().getQueryPlugin(autoPhrasingParameters.getDownstreamParser())
                .createParser(modQ, localParams, modifiableSolrParams, req);
    }

    private String filter(String qStr) {
        // 1) collapse " :" to ":" to protect field names
        // 2) expand ":" to ": " to free terms from field names
        // 3) expand "+" to "+ " to free terms from "+" operator
        // 4) expand "-" to "- " to free terms from "-" operator
        // 5) Autophrase with whitespace tokenizer
        // 6) collapse "+ " and "- " to "+" and "-" to glom operators.

        String query = qStr;
        while (query.contains(" :"))
            query = query.replaceAll("\\s:", ": ");

        query = query.replaceAll("\\+", "+ ");
        query = query.replaceAll("\\-", "- ");

        if (autoPhrasingParameters.getIgnoreCase()) {
            query = query.replaceAll("AND", "&&");
            query = query.replaceAll("OR", "||");
        }

        try {
            query = autophrase(query);
        } catch (IOException ioe) {
            Log.error(ioe.toString());
        }

        query = query.replaceAll("\\+ ", "+");
        query = query.replaceAll("\\- ", "-");

        if (autoPhrasingParameters.getIgnoreCase()) {
            query = query.replaceAll("&&", "AND");
            query = query.replaceAll("\\|\\|", "OR");
        }

        return query;
    }

    private String autophrase(String input) throws IOException {
        WhitespaceTokenizer wt = new WhitespaceTokenizer(Version.LUCENE_48, new StringReader(input));
        TokenStream ts = wt;
        if (autoPhrasingParameters.getIgnoreCase()) {
            ts = new LowerCaseFilter(Version.LUCENE_48, wt);
        }
        AutoPhrasingTokenFilter autoPhrasingTokenFilter =
                new AutoPhrasingTokenFilter(Version.LUCENE_48, ts, phraseSets, false);
        autoPhrasingTokenFilter.setReplaceWhitespaceWith(autoPhrasingParameters.getReplaceWhitespaceWith());
        CharTermAttribute term = autoPhrasingTokenFilter.addAttribute(CharTermAttribute.class);
        autoPhrasingTokenFilter.reset();

        StringBuilder stringBuilder = new StringBuilder();
        while (autoPhrasingTokenFilter.incrementToken()) {
            stringBuilder.append(term.toString()).append(" ");
        }

        return stringBuilder.toString();
    }

    @Override
    public void inform(ResourceLoader loader) throws IOException {
        List<String> phraseSetFiles = autoPhrasingParameters.getIndividualPhraseSetFiles();
        phraseSets = getWordSet(loader, phraseSetFiles, true);
    }

    private CharArraySet getWordSet(ResourceLoader loader,
                                    List<String> files, boolean ignoreCase)
            throws IOException {

        CharArraySet words = null;
        if (files.size() > 0) {
            // default stop words list has 35 or so words, but maybe don't make it that
            // big to start
            words = new CharArraySet(Version.LUCENE_48,
                    files.size() * 10, ignoreCase);
            for (String file : files) {
                List<String> stopWords = getLines(loader, file.trim());
                words.addAll(StopFilter.makeStopSet(Version.LUCENE_48, stopWords, ignoreCase));
            }
        }
        return words;
    }

    private List<String> getLines(ResourceLoader loader, String resource) throws IOException {
        return WordlistLoader.getLines(loader.openResource(resource), StandardCharsets.UTF_8);
    }

    /**
     * Returns the phrases that were loaded, intended for testing only
     * @return The phrase set
     */
    public CharArraySet getPhrases(){
        return phraseSets;
    }
}