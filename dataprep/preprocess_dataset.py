
import cv2
import mediapipe as mp
import numpy as np
import os
import json
import logging
import argparse
from tqdm import tqdm
import matplotlib.pyplot as plt
import seaborn as sns

# Constants (Must Match Android Spec)
SEQUENCE_LENGTH = 30
INPUT_DIM = 171
POSE_INDICES = list(range(15)) # 0..14

# Setup Logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

mp_holistic = mp.solutions.holistic

def extract_keypoints(results):
    """
    Extracts 171-dim feature vector from MediaPipe results.
    Strictly matches Android implementation.
    Output: [Left(63) | Right(63) | Pose(45)]
    """
    # 1. Left Hand (0-62)
    lh = np.zeros(63)
    if results.left_hand_landmarks:
        temp = []
        for lm in results.left_hand_landmarks.landmark:
            temp.extend([lm.x, lm.y, lm.z]) # NO normalization
        lh = np.array(temp)
    
    # 2. Right Hand (63-125)
    rh = np.zeros(63)
    if results.right_hand_landmarks:
        temp = []
        for lm in results.right_hand_landmarks.landmark:
            temp.extend([lm.x, lm.y, lm.z])
        rh = np.array(temp)
        
    # 3. Pose (126-170) -> First 15 landmarks
    pose = np.zeros(45)
    if results.pose_landmarks:
        temp = []
        for i in POSE_INDICES:
            lm = results.pose_landmarks.landmark[i]
            temp.extend([lm.x, lm.y, lm.z])
        pose = np.array(temp)
    
    return np.concatenate([lh, rh, pose])

def augment_mirror_frame(features):
    """
    Simulates mirroring by swapping Left/Right blocks and flipping X coords.
    Input: (171,) array
    Output: (171,) array
    """
    # Create copy
    mirrored = features.copy()
    
    # Extract blocks
    lh = features[0:63].reshape(-1, 3)
    rh = features[63:126].reshape(-1, 3)
    pose = features[126:171].reshape(-1, 3)
    
    # Flip X coordinates (Index 0 of each point) for all groups
    # Assuming input is [0,1], mirrored becomes (1.0 - x)
    # BUT MediaPipe normalized coords are 0..1. Android uses raw.
    # So we do 1.0 - x.
    lh[:, 0] = 1.0 - lh[:, 0]
    rh[:, 0] = 1.0 - rh[:, 0]
    pose[:, 0] = 1.0 - pose[:, 0]
    
    # Swap Hands: Mirrored Left becomes Right, Mirrored Right becomes Left
    # Important: Flatten back to 1D
    new_lh = rh.flatten()
    new_rh = lh.flatten()
    new_pose = pose.flatten()
    
    # Reassemble: Left slot gets old Right data, Right slot gets old Left data
    mirrored_features = np.concatenate([new_lh, new_rh, new_pose])
    
    return mirrored_features

def process_video(file_path):
    """
    Processes a single video file.
    Returns: List of sequences (N, 30, 171)
    """
    cap = cv2.VideoCapture(file_path)
    sequences = []
    frame_window = [] # Sliding buffer
    
    # Video Mode context
    with mp_holistic.Holistic(
        min_detection_confidence=0.5, 
        min_tracking_confidence=0.5,
        static_image_mode=False, # VIDEO MODE
        model_complexity=1
    ) as holistic:
        
        while cap.isOpened():
            ret, frame = cap.read()
            if not ret:
                break
            
            # Convert color
            image = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            image.flags.writeable = False
            results = holistic.process(image)
            
            # Extract
            keypoints = extract_keypoints(results)
            
            # Logic: Reset buffer if HANDS missing?
            # Android: "if (leftHandLm != null || rightHandLm != null) add else clear"
            # Here, checks zero vectors.
            has_left = np.sum(keypoints[0:63]) != 0
            has_right = np.sum(keypoints[63:126]) != 0
            
            if has_left or has_right:
                frame_window.append(keypoints)
            else:
                if len(frame_window) > 0:
                    logging.debug(f"  [Buffer Reset] Hands lost at frame end. Cleared {len(frame_window)} frames.")
                    frame_window = [] # Clear buffer logic match
                    
            if len(frame_window) == SEQUENCE_LENGTH:
                sequences.append(np.array(frame_window))
                # Slide window: Remove first item (Overlap striding)
                frame_window.pop(0) 
                
    cap.release()
    return sequences

def analyze_features(X, output_path):
    """
    Plots histograms of X, Y, Z for Left Hand, Right Hand, Pose.
    """
    logger.info("Generating feature distribution plots...")
    
    # Indices
    indices = {
        'Left Hand': (0, 63),
        'Right Hand': (63, 126),
        'Pose': (126, 171)
    }
    
    # Flatten across samples and time: [N*30, 171]
    flat_X = X.reshape(-1, 171)
    # Remove all-zero padding frames for stats (approx)
    mask = ~np.all(flat_X == 0, axis=1)
    valid_X = flat_X[mask]
    
    if len(valid_X) == 0:
        logger.warning("No valid frames for analysis!")
        return

    fig, axes = plt.subplots(3, 3, figsize=(18, 12))
    coords = ['X', 'Y', 'Z']
    
    stats_log = []
    
    for row, (part, (start, end)) in enumerate(indices.items()):
        # Extract part data: [Frames, Dims]
        part_data = valid_X[:, start:end]
        
        # Reshape to (Points, 3) 
        # Left/Right: 21 points, Pose: 15 points
        num_points = (end - start) // 3
        part_reshaped = part_data.reshape(-1, num_points, 3)
        
        # Flatten by coordinate
        for col, coord in enumerate(coords):
            vals = part_reshaped[:, :, col].flatten()
            
            # Plot
            ax = axes[row, col]
            sns.histplot(vals, bins=50, ax=ax, kde=True, color='skyblue')
            ax.set_title(f'{part} - {coord}')
            ax.set_xlim([np.min(vals), np.max(vals)])
            
            # Stats
            stat = f"{part} {coord}: Min={np.min(vals):.3f}, Max={np.max(vals):.3f}, Mean={np.mean(vals):.3f}, Std={np.std(vals):.3f}"
            stats_log.append(stat)
            
    plt.tight_layout()
    plt.savefig(os.path.join(output_path, 'feature_distributions.png'))
    logger.info("Saved feature_distributions.png")
    
    with open(os.path.join(output_path, 'feature_stats.txt'), 'w') as f:
        f.write("\n".join(stats_log))
    for s in stats_log: print(s)


def main(dataset_path, output_path, debug_dump=False):
    # Ensure dir exists
    os.makedirs(output_path, exist_ok=True)
    
    if debug_dump:
        logger.setLevel(logging.DEBUG)
    
    # Load labels
    try:
        actions = sorted(os.listdir(dataset_path))
    except FileNotFoundError:
        logger.error(f"Dataset path not found: {dataset_path}")
        return

    label_map = {label:num for num, label in enumerate(actions)}
    
    # Save mapping 
    with open(os.path.join(output_path, 'label_map.json'), 'w') as f:
        json.dump(label_map, f)
        
    X_data = []
    y_data = []
    
    print("Starting Processing...")
    
    first_sequence_captured = False

    for action in actions:
        action_path = os.path.join(dataset_path, action)
        if not os.path.isdir(action_path): continue
            
        videos = os.listdir(action_path)
        print(f"Processing Class: {action} ({len(videos)} videos)")
        
        for video in tqdm(videos):
             vid_path = os.path.join(action_path, video)
             
             # Process Original
             seqs = process_video(vid_path)
             if len(seqs) == 0: continue
             
             # Debug Dump: First valid sequence found
             if debug_dump and not first_sequence_captured:
                 debug_seq = seqs[0] # (30, 171)
                 np.save(os.path.join(output_path, 'debug_sequence.npy'), debug_seq)
                 
                 print("\n--- DEBUG DUMP: Single Sequence ---")
                 print(f"Shape: {debug_seq.shape}")
                 print(f"Frame 0 (First 20 vals): {debug_seq[0, :20]}")
                 
                 # Check Zero Padding
                 zero_frames = np.where(~debug_seq.any(axis=1))[0]
                 print(f"Zero-Padded Frame Indices: {zero_frames}")
                 print("Saved debug_sequence.npy")
                 first_sequence_captured = True
                 
             X_data.extend(seqs)
             y_data.extend([label_map[action]] * len(seqs))
             
             # Augmentation
             mirrored_seqs = [np.array([augment_mirror_frame(frame) for frame in seq]) for seq in seqs]
             X_data.extend(mirrored_seqs)
             y_data.extend([label_map[action]] * len(mirrored_seqs))

    if len(X_data) == 0:
        print("No data found!")
        return

    X = np.array(X_data)
    y = np.array(y_data)
    
    print(f"Complete. X Shape: {X.shape}, y Shape: {y.shape}")
    
    # Diagnostic Plots
    analyze_features(X, output_path)
    
    # Save
    np.save(os.path.join(output_path, 'X.npy'), X)
    np.save(os.path.join(output_path, 'y.npy'), y)

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', required=True, help='Path to dataset folders')
    parser.add_argument('--output', required=True, help='Path to save .npy files')
    parser.add_argument('--debug', action='store_true', help='Enable debug dumping')
    args = parser.parse_args()
    main(args.dataset, args.output, args.debug)
