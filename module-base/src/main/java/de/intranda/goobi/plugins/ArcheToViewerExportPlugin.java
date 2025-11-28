package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.goobi.api.ArcheConfiguration;
import org.goobi.api.rest.ArcheAPI;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IExportPlugin;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.export.download.ExportMets;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.XmlTools;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ExportFileException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.helper.exceptions.UghHelperException;
import jakarta.ws.rs.client.Client;
import lombok.Getter;
import lombok.Setter;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.exceptions.DocStructHasNoTypeException;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class ArcheToViewerExportPlugin implements IExportPlugin, IPlugin {

    private static final long serialVersionUID = 5900921810436024070L;

    @Getter
    private String title = "intranda_export_arche_to_viewer";

    @Getter
    private PluginType type = PluginType.Export;

    @Getter
    @Setter
    private Step step;

    @Getter
    private List<String> problems;

    private ArcheConfiguration archeConfiguration;

    private static final Namespace metsNamespace = Namespace.getNamespace("mets", "http://www.loc.gov/METS/");
    private static final Namespace modsNamespace = Namespace.getNamespace("mods", "http://www.loc.gov/mods/v3");
    private static final Namespace xlinkNamespace = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");

    @Override
    public void setExportFulltext(boolean arg0) {
    }

    @Override
    public void setExportImages(boolean arg0) {
    }

    @Override
    public boolean startExport(Process process) throws IOException, InterruptedException, DocStructHasNoTypeException, PreferencesException,
            WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException, SwapException, DAOException,
            TypeNotAllowedForParentException {
        String benutzerHome = process.getProjekt().getDmsImportImagesPath();
        return startExport(process, benutzerHome);
    }

    @Override
    public boolean startExport(Process process, String destination) throws IOException, InterruptedException, DocStructHasNoTypeException,
            PreferencesException, WriteException, MetadataTypeNotAllowedException, ExportFileException, UghHelperException, ReadException,
            SwapException, DAOException, TypeNotAllowedForParentException {

        problems = new ArrayList<>();

        // first validate if project, process, files are ingested into arche instance
        archeConfiguration = new ArcheConfiguration("intranda_administration_arche_project_export");
        String idPrefix = archeConfiguration.getIdentifierPrefix();
        String projectIdentifier = null;
        String processIdentifier = null;
        String mediaFolderIdentifier = null;
        String ocrFolderIdentifier = null;
        try (Client client = ArcheAPI.getClient(archeConfiguration.getArcheUserName(true), archeConfiguration.getArchePassword(true))) {

            projectIdentifier = idPrefix + process.getProjekt().getTitel();

            String resourceUri = ArcheAPI.findResourceURI(client, archeConfiguration.getArcheApiUrl(true), projectIdentifier);
            if (StringUtils.isBlank(resourceUri)) {
                problems.add("Project was not exported to Arche.");
                return false;
            }

            processIdentifier = projectIdentifier + "/" + process.getTitel();

            resourceUri = ArcheAPI.findResourceURI(client, archeConfiguration.getArcheApiUrl(true), processIdentifier);
            if (StringUtils.isBlank(resourceUri)) {
                problems.add("Process was not exported to Arche.");
                return false;
            }

            mediaFolderIdentifier = processIdentifier + "/" + process.getTitel() + "_media/";
            ocrFolderIdentifier = processIdentifier + "/" + process.getTitel() + "_ocr/";

            for (String filename : StorageProvider.getInstance().list(process.getImagesTifDirectory(false))) {
                String imageIdentifier = mediaFolderIdentifier + filename;
                resourceUri = ArcheAPI.findResourceURI(client, archeConfiguration.getArcheApiUrl(true), imageIdentifier);
                if (StringUtils.isBlank(resourceUri)) {
                    problems.add("Image " + filename + " was not exported to Arche.");
                    return false;
                }

            }

            for (String filename : StorageProvider.getInstance().list(process.getOcrAltoDirectory())) {
                String ocrIdentifier = ocrFolderIdentifier + filename;
                resourceUri = ArcheAPI.findResourceURI(client, archeConfiguration.getArcheApiUrl(true), ocrIdentifier);
                if (StringUtils.isBlank(resourceUri)) {
                    problems.add("ALTO file " + filename + " was not exported to Arche.");
                    return false;
                }
            }
        }

        // regular export into temp folder
        ExportMets export = new ExportMets();
        boolean success = export.startExport(process, ConfigurationHelper.getInstance().getTemporaryFolder());
        Path metsfile = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), process.getTitel() + "_mets.xml");
        Path anchorfile = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), process.getTitel() + "_anchor_mets.xml");
        if (!success) {
            problems.addAll(export.getProblems());
            // remove temp files
            if (StorageProvider.getInstance().isFileExists(metsfile)) {
                StorageProvider.getInstance().deleteFile(metsfile);
            }
            if (StorageProvider.getInstance().isFileExists(anchorfile)) {
                StorageProvider.getInstance().deleteFile(anchorfile);
            }

            return false;
        }

        // read exported file
        Document doc = XmlTools.readDocumentFromFile(metsfile);
        Element mets = doc.getRootElement();

        // get main metadata section
        List<Element> dmdSecs = mets.getChildren("dmdSec", metsNamespace);
        Element dmdSec = dmdSecs.get(0);
        Element mods = dmdSec.getChild("mdWrap", metsNamespace).getChild("xmlData", metsNamespace).getChild("mods", modsNamespace);

        // add process resource url as mods:identifier
        Element identifier = new Element("identifier", modsNamespace);
        identifier.setText(processIdentifier);
        identifier.setAttribute("type", "arche");
        mods.addContent(identifier);

        // replace file groups with arche iiif urls
        Element fileSec = mets.getChild("fileSec", metsNamespace);

        // find filegroups for presentation, thumbs,  alto/fulltext
        for (Element fileGrp : fileSec.getChildren("fileGrp", metsNamespace)) {
            String name = fileGrp.getAttributeValue("USE");
            List<Element> filesInGrp = fileGrp.getChildren("file", metsNamespace);
            for (Element fileElement : filesInGrp) {
                Element flocat = fileElement.getChild("FLocat", metsNamespace);
                flocat.setAttribute("type", "simple", xlinkNamespace);
                String filename = Paths.get(flocat.getAttributeValue("href", xlinkNamespace)).getFileName().toString();

                switch (name.toLowerCase()) {
                    case "presentation", "default":
                        flocat.setAttribute("href", mediaFolderIdentifier + filename + "?format=image%2Fjpeg", xlinkNamespace);
                        fileElement.setAttribute("MIMETYPE", "image/jpeg");
                        break;
                    case "thumbs", "thumbnail", "thumbnails":
                        flocat.setAttribute("href", mediaFolderIdentifier + filename + "?format=thumbnail", xlinkNamespace);
                        fileElement.setAttribute("MIMETYPE", "image/png");
                        break;
                    case "alto", "fulltext":
                        flocat.setAttribute("href", ocrFolderIdentifier + filename, xlinkNamespace);
                        fileElement.setAttribute("MIMETYPE", "application/xml");
                        break;
                }

            }
        }

        // save xml file
        XMLOutputter xmlOut = new XMLOutputter(Format.getPrettyFormat());
        xmlOut.output(doc, StorageProvider.getInstance().newOutputStream(metsfile));

        // move results to destination
        StorageProvider.getInstance().move(metsfile, Paths.get(destination, metsfile.getFileName().toString()));

        if (StorageProvider.getInstance().isFileExists(anchorfile)) {
            StorageProvider.getInstance().move(anchorfile, Paths.get(destination, anchorfile.getFileName().toString()));
        }
        return success;
    }

}