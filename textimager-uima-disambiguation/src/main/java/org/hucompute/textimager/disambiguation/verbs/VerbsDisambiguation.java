package org.hucompute.textimager.disambiguation.verbs;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.component.JCasAnnotator_ImplBase;
import org.apache.uima.fit.descriptor.ConfigurationParameter;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;

import com.github.jfasttext.JFastText;
import com.github.jfasttext.JFastText.ProbLabel;

import de.tudarmstadt.ukp.dkpro.core.api.lexmorph.type.pos.V;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.CasConfigurableProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ModelProviderBase;
import de.tudarmstadt.ukp.dkpro.core.api.resources.ResourceUtils;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;
import de.tudarmstadt.ukp.dkpro.core.api.semantics.type.WordSense;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.CCOMP;
import de.tudarmstadt.ukp.dkpro.core.api.syntax.type.dependency.Dependency;
import de.tuebingen.uni.sfs.germanet.api.GermaNet;
import de.tuebingen.uni.sfs.germanet.api.WordCategory;


public class VerbsDisambiguation extends JCasAnnotator_ImplBase{

	/**
	 * Location from which the model is read.
	 */
	public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
	@ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
	protected String modelLocation;
	private CasConfigurableProviderBase<JFastText> modelProvider;

	/**
	 * Variant of a model the model. Used to address a specific model if here are multiple models
	 * for one language.
	 */
	public static final String PARAM_VARIANT = ComponentParameters.PARAM_VARIANT;
	@ConfigurationParameter(name = PARAM_VARIANT, mandatory = false)
	protected String variant;

	public static final String PARAM_GERMANET_PATH = "germanetPath";
	@ConfigurationParameter(name = PARAM_GERMANET_PATH, mandatory = true)
	protected String germanetPath;


	public static final String PARAM_VERBLEMMAIDS_PATH = "VERBLEMMAIDSPath";
	@ConfigurationParameter(name = PARAM_VERBLEMMAIDS_PATH, mandatory = true)
	protected String verblemmaIdsPath;

	HashMap<String, HashSet<String>>verbLemmaIds = new HashMap<>();
	GermaNet gnet;

	@Override
	public void initialize(UimaContext context) throws ResourceInitializationException {
		super.initialize(context);
		try {
			gnet = new GermaNet(new File(germanetPath));
		} catch (XMLStreamException | IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		modelProvider = new ModelProviderBase<JFastText>()
		{
			{
				setContextObject(VerbsDisambiguation.this);

				setDefault(ARTIFACT_ID, "${groupId}.fasttext-languageidentification-model-${language}-${variant}");
				setDefault(LOCATION,
						"classpath:/${package}/lib/verbsdisambiguation-${language}-${variant}.properties");
				setDefault(VARIANT, "small");

				setOverride(LOCATION, modelLocation);
				setOverride(LANGUAGE, "de");
				setOverride(VARIANT, variant);
			}

			@Override
			protected JFastText produceResource(URL aUrl)
					throws IOException
			{
				JFastText fasttext = new JFastText();
				File profileFolder = ResourceUtils.getUrlAsFile(aUrl, true);
				fasttext.loadModel(profileFolder.getAbsolutePath());
				return fasttext;
			}
		};
		try {
			List<String> lines;
			if(verbLemmaIds == null)
				lines = IOUtils.readLines(getClass().getClassLoader().getResourceAsStream("org/hucompute/textimager/disambiguation/verbs/lib/verbLemmaIds"));
			else
				lines = FileUtils.readLines(new File(verblemmaIdsPath));
			
			for (String string : lines) {
				String[]split = string.split("\t");
				HashSet<String>ids = new HashSet<>();
				for (int i = 1; i < split.length; i++) {
					ids.add(split[i]);
				}
				verbLemmaIds.put(split[0], ids);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	@Override
	public void process(JCas aJCas) throws AnalysisEngineProcessException {

		modelProvider.configure(aJCas.getCas());
		for (Sentence sentence : JCasUtil.select(aJCas, Sentence.class)) {
			aJCas.getCas().createAnnotation(sentence.getType(), 1, 0);

			List<Dependency>svps = new ArrayList<>();
			for (Dependency dependency : JCasUtil.selectCovered(Dependency.class, sentence)) {
				if(dependency.getDependencyType().equals("SVP"))
					svps.add(dependency);
			}
			for (Token token : JCasUtil.selectCovered(Token.class, sentence)) {
				String lemma = token.getLemma().getValue();
				for (Dependency dependency : svps) {
					if(dependency.getGovernor().equals(token)){
						lemma = dependency.getDependent().getLemma().getValue()+lemma;
						token.getLemma().setValue(lemma);
					}
				}
				if(gnet.getLexUnits(lemma, WordCategory.verben).size() == 1){
					WordSense sense = new WordSense(aJCas, token.getBegin(), token.getEnd());
					sense.setValue(Integer.toString(gnet.getLexUnits(lemma, WordCategory.verben).get(0).getId()));
					sense.addToIndexes();
					continue;
				}

				if(token.getPos().getClass() == V.class && verbLemmaIds.containsKey(lemma)){

					String toAnalize = sentence.getCoveredText();
					for (String string : sentence.getCoveredText().split(",|;|:| und ")) {
						if(sentence.getCoveredText().indexOf(string) <= (token.getBegin()-sentence.getBegin()) && sentence.getCoveredText().indexOf(string)+string.length() >= token.getEnd()-sentence.getBegin())
						{
							toAnalize = (string);
							break;
						}
					}
					toAnalize = toAnalize.replace("\"", " \"")
							.replace(",", " ,")
							.replace(".", " . ")
							.replace(":", " :")
							.replace(";", " ;")
							.replace("?", " ?")
							.replace("!", " !")
							.replace("(", " (")
							.replace(")", " )")
							.replace("-", " -")
							.replaceAll(" -(\\w)", " - $1").trim();
					System.out.println(toAnalize);
					List<ProbLabel> probLabel = modelProvider.getResource().predictProba(toAnalize,100000);
					for (ProbLabel probLabel2 : probLabel) {
						if(verbLemmaIds.get(lemma).contains(probLabel2.label.replace("__label__", ""))){
							WordSense sense = new WordSense(aJCas, token.getBegin(), token.getEnd());
							sense.setValue(probLabel2.label.replace("__label__", ""));
							sense.addToIndexes();
							break;
						}
					}
				}
			}
		}
	}

}