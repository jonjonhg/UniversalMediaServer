/*
 * PS3 Media Server, for streaming any medias to your PS3.
 * Copyright (C) 2008  A.Brochard
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; version 2
 * of the License only.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package net.pms.dlna;

import java.io.*;
import java.util.ArrayList;
import net.pms.PMS;
import net.pms.configuration.PmsConfiguration;
import net.pms.formats.Format;
import net.pms.formats.FormatFactory;
import net.pms.util.FileUtil;
import net.pms.util.ProcessUtil;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealFile extends MapFile {
	private static final Logger LOGGER = LoggerFactory.getLogger(RealFile.class);
	private static final PmsConfiguration configuration = PMS.getConfiguration();

	public RealFile(File file) {
		getConf().getFiles().add(file);
		setLastModified(file.lastModified());
	}

	public RealFile(File file, String name) {
		getConf().getFiles().add(file);
		getConf().setName(name);
		setLastModified(file.lastModified());
	}

	@Override
	// FIXME: this is called repeatedly for invalid files e.g. files MediaInfo can't parse
	public boolean isValid() {
		File file = this.getFile();
		checktype();
		if (getType() == Format.VIDEO && file.exists() && configuration.isAutoloadSubtitles() && file.getName().length() > 4) {
			setSrtFile(FileUtil.isSubtitlesExists(file, null));
		}

		boolean valid = file.exists() && (getFormat() != null || file.isDirectory());

		if (valid && getParent().getDefaultRenderer() != null && getParent().getDefaultRenderer().isMediaParserV2()) {
			// we need to resolve the DLNA resource now
			run();

			if (getMedia() != null && getMedia().getThumb() == null && getType() != Format.AUDIO) { // MediaInfo retrieves cover art now
				getMedia().setThumbready(false);
			}

			// Given that here getFormat() has already matched some (possibly plugin-defined) format:
			//    Format.UNKNOWN + bad parse = inconclusive
			//    known types    + bad parse = bad/encrypted file
			if (getType() != Format.UNKNOWN && getMedia() != null && (getMedia().isEncrypted() || getMedia().getContainer() == null || getMedia().getContainer().equals(DLNAMediaLang.UND))) {
				valid = false;
				if (getMedia().isEncrypted()) {
					LOGGER.info("The file {} is encrypted. It will be hidden", file.getAbsolutePath());
				} else {
					LOGGER.info("The file {} was badly parsed. It will be hidden", file.getAbsolutePath());
				}
			}

			// XXX isMediaParserV2ThumbnailGeneration is only true for the "default renderer"
			if (getParent().getDefaultRenderer().isMediaParserV2ThumbnailGeneration()) {
				checkThumbnail();
			}
		}

		return valid;
	}

	@Override
	public InputStream getInputStream() {
		try {
			return new FileInputStream(getFile());
		} catch (FileNotFoundException e) {
			LOGGER.debug("File not found: {}", getFile().getAbsolutePath());
		}

		return null;
	}

	@Override
	public long length() {
		if (getPlayer() != null && getPlayer().type() != Format.IMAGE) {
			return DLNAMediaInfo.TRANS_SIZE;
		} else if (getMedia() != null && getMedia().isMediaparsed()) {
			return getMedia().getSize();
		}
		return getFile().length();
	}

	@Override
	public boolean isFolder() {
		return getFile().isDirectory();
	}

	public File getFile() {
		return getConf().getFiles().get(0);
	}

	@Override
	public String getName() {
		if (this.getConf().getName() == null) {
			String name = null;
			File file = getFile();
			if (file.getName().trim().isEmpty()) {
				if (PMS.get().isWindows()) {
					name = PMS.get().getRegistry().getDiskLabel(file);
				}
				if (name != null && name.length() > 0) {
					name = file.getAbsolutePath().substring(0, 1) + ":\\ [" + name + "]";
				} else {
					name = file.getAbsolutePath().substring(0, 1);
				}
			} else {
				name = file.getName();
			}
			this.getConf().setName(name);
		}
		return this.getConf().getName().replaceAll("_imdb([^_]+)_", "");
	}

	@Override
	protected void checktype() {
		if (getFormat() == null) {
			setFormat(FormatFactory.getAssociatedExtension(getFile().getAbsolutePath()));
		}

		super.checktype();
	}

	@Override
	public String getSystemName() {
		return ProcessUtil.getShortFileNameIfWideChars(getFile().getAbsolutePath());
	}

	@Override
	public void resolve() {
		File file = getFile();
		if (file.isFile() && (getMedia() == null || !getMedia().isMediaparsed())) {
			boolean found = false;
			InputFile input = new InputFile();
			input.setFile(file);
			String fileName = file.getAbsolutePath();
			if (getSplitTrack() > 0) {
				fileName += "#SplitTrack" + getSplitTrack();
			}
			
			if (configuration.getUseCache()) {
				DLNAMediaDatabase database = PMS.get().getDatabase();

				if (database != null) {
					ArrayList<DLNAMediaInfo> medias = database.getData(fileName, file.lastModified());

					if (medias != null && medias.size() == 1) {
						setMedia(medias.get(0));
						getMedia().finalize(getType(), input);
						found = true;
					}
				}
			}

			if (!found) {
				if (getMedia() == null) {
					setMedia(new DLNAMediaInfo());
				}

				found = !getMedia().isMediaparsed() && !getMedia().isParsing();

				if (getFormat() != null) {
					getFormat().parse(getMedia(), input, getType(), getParent().getDefaultRenderer());
				} else {
					// Don't think that will ever happen
					getMedia().parse(input, getFormat(), getType(), false);
				}

				if (found && configuration.getUseCache()) {
					DLNAMediaDatabase database = PMS.get().getDatabase();

					if (database != null) {
						database.insertData(fileName, file.lastModified(), getType(), getMedia());
					}
				}
			}
		}
		super.resolve();
	}

	@Override
	public String getThumbnailContentType() {
		return super.getThumbnailContentType();
	}

	@Override
	public InputStream getThumbnailInputStream() throws IOException {
		File file = getFile();
		File cachedThumbnail = null;

		if (getParent() != null && getParent() instanceof RealFile) {
			cachedThumbnail = ((RealFile) getParent()).getPotentialCover();
			File thumbFolder = null;
			boolean alternativeCheck = false;

			while (cachedThumbnail == null) {
				if (thumbFolder == null && getType() != Format.IMAGE) {
					thumbFolder = file.getParentFile();
				}

				cachedThumbnail = FileUtil.getFileNameWithNewExtension(thumbFolder, file, "jpg");

				if (cachedThumbnail == null) {
					cachedThumbnail = FileUtil.getFileNameWithNewExtension(thumbFolder, file, "png");
				}

				if (cachedThumbnail == null) {
					cachedThumbnail = FileUtil.getFileNameWithAddedExtension(thumbFolder, file, ".cover.jpg");
				}

				if (cachedThumbnail == null) {
					cachedThumbnail = FileUtil.getFileNameWithAddedExtension(thumbFolder, file, ".cover.png");
				}

				if (alternativeCheck) {
					break;
				}

				if (StringUtils.isNotBlank(configuration.getAlternateThumbFolder())) {
					thumbFolder = new File(configuration.getAlternateThumbFolder());

					if (!thumbFolder.isDirectory()) {
						thumbFolder = null;
						break;
					}
				}

				alternativeCheck = true;
			}

			if (file.isDirectory()) {
				cachedThumbnail = FileUtil.getFileNameWithNewExtension(file.getParentFile(), file, "/folder.jpg");

				if (cachedThumbnail == null) {
					cachedThumbnail = FileUtil.getFileNameWithNewExtension(file.getParentFile(), file, "/folder.png");
				}
			}
		}

		boolean hasAlreadyEmbeddedCoverArt = getType() == Format.AUDIO && getMedia() != null && getMedia().getThumb() != null;

		if (cachedThumbnail != null && (!hasAlreadyEmbeddedCoverArt || file.isDirectory())) {
			return new FileInputStream(cachedThumbnail);
		} else if (getMedia() != null && getMedia().getThumb() != null) {
			return getMedia().getThumbnailInputStream();
		} else {
			return super.getThumbnailInputStream();
		}
	}

	@Override
	public void checkThumbnail() {
		InputFile input = new InputFile();
		input.setFile(getFile());
		checkThumbnail(input);
	}

	@Override
	protected String getThumbnailURL() {
		if (getType() == Format.IMAGE && !configuration.getImageThumbnailsEnabled()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(PMS.get().getServer().getURL());
		sb.append("/");
		if (getMedia() != null && getMedia().getThumb() != null) {
			return super.getThumbnailURL();
		} else if (getType() == Format.AUDIO) {
			if (getParent() != null && getParent() instanceof RealFile && ((RealFile) getParent()).getPotentialCover() != null) {
				return super.getThumbnailURL();
			}
			return null;
		}
		return super.getThumbnailURL();
	}
}
