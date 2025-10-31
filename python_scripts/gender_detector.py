#!/usr/bin/env python3
"""
Gender Detector - Detecta o gênero de cada speaker em segmentos de áudio
Usa inaSpeechSegmenter (CNN-based) para análise de gênero
"""

import sys
import json
import os
from pathlib import Path

def detect_speaker_genders(audio_file, vtt_file):
    """
    Detecta o gênero de cada speaker no arquivo de áudio

    Args:
        audio_file: Caminho para o arquivo de áudio (vocals.wav)
        vtt_file: Caminho para o arquivo VTT com marcadores [SPEAKER_XX]

    Returns:
        Dict com mapeamento {speaker_id: gender}
        Exemplo: {"SPEAKER_00": "female", "SPEAKER_01": "male"}
    """

    try:
        # Lazy import para evitar erro se não estiver instalado
        from inaSpeechSegmenter import Segmenter
        from pydub import AudioSegment
    except ImportError as e:
        print(json.dumps({
            "error": "Dependências não instaladas",
            "details": str(e),
            "install_command": "pip install inaSpeechSegmenter pydub"
        }), file=sys.stderr)
        sys.exit(1)

    # Verificar se arquivos existem
    if not os.path.exists(audio_file):
        print(json.dumps({"error": f"Arquivo de áudio não encontrado: {audio_file}"}), file=sys.stderr)
        sys.exit(1)

    if not os.path.exists(vtt_file):
        print(json.dumps({"error": f"Arquivo VTT não encontrado: {vtt_file}"}), file=sys.stderr)
        sys.exit(1)

    print(f"🔍 Analisando áudio: {audio_file}", file=sys.stderr)
    print(f"📋 Lendo speakers de: {vtt_file}", file=sys.stderr)

    # 1. Ler VTT e extrair segmentos por speaker
    speaker_segments = parse_vtt_speakers(vtt_file)

    if not speaker_segments:
        print(json.dumps({"error": "Nenhum marcador de speaker encontrado no VTT"}), file=sys.stderr)
        sys.exit(1)

    print(f"👥 Encontrados {len(speaker_segments)} speakers: {list(speaker_segments.keys())}", file=sys.stderr)

    # 2. Carregar áudio completo
    audio = AudioSegment.from_wav(audio_file)

    # 3. Inicializar segmentador de gênero
    print("🧠 Inicializando modelo CNN de detecção de gênero...", file=sys.stderr)
    segmenter = Segmenter(detect_gender=True)

    # 4. Analisar cada speaker (em ordem numérica)
    speaker_genders = {}
    temp_dir = Path(audio_file).parent / "temp_gender_analysis"
    temp_dir.mkdir(exist_ok=True)

    # Ordenar speakers ALFABETICAMENTE para consistência com AutomaticVoiceSelector.java
    # Isso garante que a ordem de detecção seja a mesma da atribuição de vozes
    sorted_speakers = sorted(speaker_segments.items(), key=lambda x: x[0])

    for speaker_id, segments in sorted_speakers:
        print(f"\n🎤 Analisando {speaker_id} ({len(segments)} segmentos)...", file=sys.stderr)

        # Pegar até 45 segundos de fala desse speaker (distribuídos ao longo do áudio)
        # Estratégia: pegar amostras do início, meio e fim para melhor representatividade
        total_duration = 0
        combined_audio = AudioSegment.empty()
        max_duration = 45000  # 45 segundos em ms

        # Dividir segmentos em 3 partes (início, meio, fim)
        num_segments = len(segments)
        samples_per_part = max(1, num_segments // 3)

        # Início (primeiros segmentos)
        for start, end in segments[:samples_per_part]:
            if total_duration >= max_duration:
                break
            segment_audio = audio[start:end]
            combined_audio += segment_audio
            total_duration += (end - start)

        # Meio (segmentos centrais)
        if total_duration < max_duration and num_segments > samples_per_part:
            mid_start = num_segments // 2 - samples_per_part // 2
            mid_end = mid_start + samples_per_part
            for start, end in segments[mid_start:mid_end]:
                if total_duration >= max_duration:
                    break
                segment_audio = audio[start:end]
                combined_audio += segment_audio
                total_duration += (end - start)

        # Fim (últimos segmentos)
        if total_duration < max_duration and num_segments > samples_per_part * 2:
            for start, end in segments[-samples_per_part:]:
                if total_duration >= max_duration:
                    break
                segment_audio = audio[start:end]
                combined_audio += segment_audio
                total_duration += (end - start)

        # Salvar temporariamente para análise
        temp_file = temp_dir / f"{speaker_id}.wav"
        combined_audio.export(temp_file, format="wav")

        # Analisar gênero
        print(f"   Analisando {total_duration/1000:.1f}s de áudio...", file=sys.stderr)
        segmentation = segmenter(str(temp_file))

        # Contar votos de gênero
        gender_votes = {"male": 0, "female": 0}
        total_time = 0

        print(f"   🔍 Segmentação detalhada:", file=sys.stderr)
        for label, start, end in segmentation:
            duration = end - start
            print(f"      {start:.2f}s - {end:.2f}s: {label} ({duration:.2f}s)", file=sys.stderr)
            if label in ["male", "female"]:
                gender_votes[label] += duration
                total_time += duration

        # Determinar gênero majoritário
        if total_time > 0:
            male_ratio = gender_votes["male"] / total_time
            female_ratio = gender_votes["female"] / total_time

            print(f"   📊 Votos totais: Male={gender_votes['male']:.2f}s ({male_ratio*100:.1f}%), Female={gender_votes['female']:.2f}s ({female_ratio*100:.1f}%)", file=sys.stderr)

            # Determinar gênero com threshold de confiança
            # Se a diferença for muito pequena (< 60%), usar pitch como validação adicional
            confidence_threshold = 0.60  # 60% de confiança mínima

            if male_ratio > female_ratio:
                detected_gender = "male"
                confidence = male_ratio
            else:
                detected_gender = "female"
                confidence = female_ratio

            # Se confiança baixa, validar com análise de pitch
            if confidence < confidence_threshold:
                print(f"   ⚠️ Confiança baixa ({confidence*100:.1f}%), validando com análise de pitch...", file=sys.stderr)
                pitch_gender = analyze_pitch_gender(combined_audio)

                # Se pitch discorda, usar pitch (mais confiável para vozes ambíguas)
                if pitch_gender != detected_gender:
                    print(f"   🔄 Pitch indica {pitch_gender.upper()}, ajustando detecção", file=sys.stderr)
                    detected_gender = pitch_gender
                    confidence = 0.7  # Confiança moderada quando usa pitch

            speaker_genders[speaker_id] = detected_gender
            print(f"   ✅ {speaker_id}: {detected_gender.upper()} (confiança: {confidence*100:.1f}%)", file=sys.stderr)
        else:
            # Fallback: usar análise de pitch se não detectou fala
            print(f"   ⚠️ Sem detecção de gênero, usando análise de pitch...", file=sys.stderr)
            detected_gender = analyze_pitch_gender(combined_audio)
            speaker_genders[speaker_id] = detected_gender
            print(f"   ✅ {speaker_id}: {detected_gender.upper()} (via pitch)", file=sys.stderr)

        # Limpar arquivo temporário
        temp_file.unlink()

    # Limpar diretório temporário
    temp_dir.rmdir()

    # 5. Retornar resultado como JSON
    result = {
        "speakers": speaker_genders,
        "count": len(speaker_genders)
    }

    print(f"\n📊 Resultado final: {result}", file=sys.stderr)
    # Garantir que JSON está no stdout, não stderr
    print(json.dumps(result), flush=True)
    return result

def parse_vtt_speakers(vtt_file):
    """
    Parse arquivo VTT e extrai segmentos por speaker

    Returns:
        Dict {speaker_id: [(start_ms, end_ms), ...]}
    """
    speaker_segments = {}

    with open(vtt_file, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    current_timestamp = None

    for line in lines:
        line = line.strip()

        # Detectar timestamp
        if '-->' in line:
            parts = line.split('-->')
            start_str = parts[0].strip()
            end_str = parts[1].strip()

            # Converter timestamp VTT para milissegundos
            start_ms = timestamp_to_ms(start_str)
            end_ms = timestamp_to_ms(end_str)

            current_timestamp = (start_ms, end_ms)

        # Detectar speaker
        elif current_timestamp and line.startswith('[SPEAKER_'):
            speaker_id = line.split(']:')[0].replace('[', '')

            if speaker_id not in speaker_segments:
                speaker_segments[speaker_id] = []

            speaker_segments[speaker_id].append(current_timestamp)
            current_timestamp = None

    return speaker_segments

def timestamp_to_ms(timestamp_str):
    """
    Converte timestamp VTT para milissegundos
    Suporta: HH:MM:SS.mmm ou MM:SS.mmm
    """
    parts = timestamp_str.replace(',', '.').split(':')

    if len(parts) == 3:  # HH:MM:SS.mmm
        hours = int(parts[0])
        minutes = int(parts[1])
        seconds = float(parts[2])
        return int((hours * 3600 + minutes * 60 + seconds) * 1000)
    elif len(parts) == 2:  # MM:SS.mmm
        minutes = int(parts[0])
        seconds = float(parts[1])
        return int((minutes * 60 + seconds) * 1000)
    else:
        return 0

def analyze_pitch_gender(audio_segment):
    """
    Fallback: Analisa pitch médio para determinar gênero

    Pitch típico:
    - Masculino: 85-180 Hz
    - Feminino: 165-255 Hz
    """
    try:
        import librosa
        import numpy as np

        # Converter para array numpy
        samples = np.array(audio_segment.get_array_of_samples()).astype(np.float32)
        sample_rate = audio_segment.frame_rate

        # Normalizar
        samples = samples / np.max(np.abs(samples))

        # Extrair pitch usando librosa
        pitches, magnitudes = librosa.piptrack(y=samples, sr=sample_rate)

        # Pegar pitches com magnitude significativa
        pitch_values = []
        for t in range(pitches.shape[1]):
            index = magnitudes[:, t].argmax()
            pitch = pitches[index, t]
            if pitch > 0:
                pitch_values.append(pitch)

        if pitch_values:
            avg_pitch = np.median(pitch_values)
            print(f"   Pitch médio: {avg_pitch:.1f} Hz", file=sys.stderr)

            # Threshold: 170 Hz (entre faixas típicas)
            return "male" if avg_pitch < 170 else "female"

    except ImportError:
        print("   ⚠️ librosa não instalado, usando heurística padrão", file=sys.stderr)
    except Exception as e:
        print(f"   ⚠️ Erro na análise de pitch: {e}", file=sys.stderr)

    # Fallback: retornar male como padrão
    return "male"

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Uso: python gender_detector.py <audio_file.wav> <transcription.vtt>", file=sys.stderr)
        sys.exit(1)

    audio_file = sys.argv[1]
    vtt_file = sys.argv[2]

    detect_speaker_genders(audio_file, vtt_file)
